package com.thoughtcoding.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Short transactions, optimistic versions and immutable snapshots. No connection spans a model/tool call. */
public final class SqliteTaskStore implements TaskStorage {
    static final ObjectMapper JSON = new ObjectMapper();
    private final Path database;
    private final int busyTimeout;

    public SqliteTaskStore(Path database) { this(database, 5000); }
    SqliteTaskStore(Path database, int busyTimeout) {
        this.database = database.toAbsolutePath().normalize();
        this.busyTimeout = busyTimeout;
        try { Files.createDirectories(this.database.getParent()); }
        catch (IOException e) { throw failure("创建数据库目录失败", e); }
        initialize();
    }

    public Path databasePath() { return database; }

    Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + busyTimeout);
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA synchronous=FULL");
            return connection;
        } catch (SQLException e) { connection.close(); throw e; }
    }

    private void initialize() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet result = s.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1)))
                    throw new SQLException("无法启用 WAL");
            }
            s.execute("BEGIN IMMEDIATE");
            try {
                int version;
                try (ResultSet result = s.executeQuery("PRAGMA user_version")) { result.next(); version = result.getInt(1); }
                if (version > 1) throw new SQLException("数据库版本高于当前程序支持的版本: " + version);
                if (version == 0) {
                    s.execute("""
                        CREATE TABLE tasks (
                          task_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, parent_task_id TEXT,
                          status TEXT NOT NULL, current_version INTEGER NOT NULL CHECK(current_version > 0),
                          updated_at TEXT NOT NULL, state_json TEXT NOT NULL,
                          FOREIGN KEY(task_id, current_version) REFERENCES checkpoints(task_id, version)
                            DEFERRABLE INITIALLY DEFERRED)
                        """);
                    s.execute("""
                        CREATE TABLE checkpoints (
                          task_id TEXT NOT NULL REFERENCES tasks(task_id), version INTEGER NOT NULL CHECK(version > 0),
                          checkpoint_json TEXT NOT NULL, created_at TEXT NOT NULL,
                          PRIMARY KEY(task_id, version))
                        """);
                    s.execute("CREATE INDEX tasks_session_updated ON tasks(session_id, updated_at DESC)");
                    s.execute("CREATE INDEX tasks_parent ON tasks(parent_task_id)");
                    s.execute("CREATE INDEX tasks_status_updated ON tasks(status, updated_at DESC)");
                    s.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
                    s.execute("INSERT INTO schema_migrations VALUES(1, strftime('%Y-%m-%dT%H:%M:%fZ','now'))");
                    s.execute("CREATE TABLE legacy_imports(task_id TEXT PRIMARY KEY REFERENCES tasks(task_id), source_head TEXT NOT NULL)");
                    s.execute("PRAGMA user_version=1");
                }
                s.execute("COMMIT");
            } catch (Exception e) { rollback(c); throw e; }
        } catch (Exception e) { throw failure("初始化任务数据库失败", e); }
    }

    static ExecutionCheckpoint decode(String json, String id, long version) throws IOException {
        ExecutionCheckpoint cp = JSON.readValue(json, ExecutionCheckpoint.class);
        if (cp.schemaVersion() != 1 || !id.equals(cp.task().taskId()) || cp.task().version() != version)
            throw new IOException("检查点版本或任务 ID 不匹配");
        return cp;
    }

    @Override public ExecutionCheckpoint load(String id) {
        TaskLeaseManager.validateId(id);
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("""
                SELECT c.checkpoint_json, c.version FROM tasks t JOIN checkpoints c
                ON c.task_id=t.task_id AND c.version=t.current_version WHERE t.task_id=?
                """)) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("任务不存在: " + id);
                return decode(r.getString(1), id, r.getLong(2));
            }
        } catch (SQLException | IOException e) { throw failure("读取任务失败: " + id, e); }
    }

    @Override public boolean exists(String id) {
        TaskLeaseManager.validateId(id);
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT 1 FROM tasks WHERE task_id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        } catch (SQLException e) { throw failure("查询任务失败: " + id, e); }
    }

    @Override public List<TaskState> list(String sessionId) {
        String sql = "SELECT state_json FROM tasks" + (sessionId == null ? "" : " WHERE session_id=?") + " ORDER BY updated_at DESC, task_id";
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) {
            if (sessionId != null) s.setString(1, sessionId);
            List<TaskState> states = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) { while (r.next()) states.add(JSON.readValue(r.getString(1), TaskState.class)); }
            return List.copyOf(states);
        } catch (SQLException | IOException e) { throw failure("读取任务列表失败", e); }
    }

    @Override public List<Long> checkpoints(String id) {
        TaskLeaseManager.validateId(id);
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT version FROM checkpoints WHERE task_id=? ORDER BY version")) {
            s.setString(1, id);
            List<Long> versions = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) { while (r.next()) versions.add(r.getLong(1)); }
            if (versions.isEmpty()) throw new IllegalStateException("任务不存在: " + id);
            return List.copyOf(versions);
        } catch (SQLException e) { throw failure("读取检查点列表失败", e); }
    }

    @Override public void commit(ExecutionCheckpoint cp, long expectedVersion) {
        TaskLeaseManager.validateId(cp.task().taskId());
        if (cp.schemaVersion() != 1 || expectedVersion < 0 || expectedVersion == Long.MAX_VALUE
                || cp.task().version() != expectedVersion + 1) throw failure("无效检查点版本", null);
        try (Connection c = connect(); Statement transaction = c.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                writeState(c, cp.task(), expectedVersion);
                insertCheckpoint(c, cp);
                transaction.execute("COMMIT");
            } catch (Exception e) { rollback(c); throw e; }
        } catch (Exception e) { throw failure("任务检查点提交失败，已停止后续执行: " + cp.task().taskId(), e); }
    }

    private static void writeState(Connection c, TaskState state, long expected) throws SQLException, IOException {
        String sql = expected == 0
                ? "INSERT INTO tasks(session_id,parent_task_id,status,current_version,updated_at,state_json,task_id) VALUES(?,?,?,?,?,?,?)"
                : "UPDATE tasks SET session_id=?,parent_task_id=?,status=?,current_version=?,updated_at=?,state_json=? WHERE task_id=? AND current_version=?";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, state.sessionId()); s.setString(2, state.parentTaskId()); s.setString(3, state.status().name());
            s.setLong(4, state.version()); s.setString(5, state.updatedAt()); s.setString(6, JSON.writeValueAsString(state));
            s.setString(7, state.taskId());
            if (expected != 0) s.setLong(8, expected);
            if (s.executeUpdate() != 1) throw new SQLException("任务版本冲突: " + state.taskId());
        }
    }

    private static void insertCheckpoint(Connection c, ExecutionCheckpoint cp) throws SQLException, IOException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO checkpoints VALUES(?,?,?,?)")) {
            s.setString(1, cp.task().taskId()); s.setLong(2, cp.task().version());
            s.setString(3, JSON.writeValueAsString(cp)); s.setString(4, cp.task().updatedAt()); s.executeUpdate();
        }
    }

    /** Completed archives must never become an automatic fallback for a lost database. */
    void verifyImported(String id, String head) {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT source_head FROM legacy_imports WHERE task_id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || !head.equals(r.getString(1)))
                    throw new SQLException("已迁移任务的数据库记录丢失或不匹配，拒绝回退旧 JSON: " + id);
            }
        } catch (SQLException e) { throw failure("检查迁移记录失败", e); }
    }

    /** All legacy tasks import in one transaction; restarting never overwrites later SQLite generations. */
    void importLegacy(List<JsonTaskStore.LegacyTask> tasks) {
        if (tasks.isEmpty()) return;
        try (Connection c = connect(); Statement transaction = c.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                for (var task : tasks) {
                    String imported = null;
                    try (PreparedStatement s = c.prepareStatement("SELECT source_head FROM legacy_imports WHERE task_id=?")) {
                        s.setString(1, task.id());
                        try (ResultSet r = s.executeQuery()) { if (r.next()) imported = r.getString(1); }
                    }
                    if (imported != null) {
                        if (!imported.equals(task.head())) throw new SQLException("旧任务在迁移后被修改: " + task.id());
                        // Verify every imported snapshot, not merely the latest pointer.
                        for (var cp : task.generations()) {
                            try (PreparedStatement s = c.prepareStatement("SELECT checkpoint_json FROM checkpoints WHERE task_id=? AND version=?")) {
                                s.setString(1, task.id()); s.setLong(2, cp.task().version());
                                try (ResultSet r = s.executeQuery()) {
                                    if (!r.next() || !decode(r.getString(1), task.id(), cp.task().version()).equals(cp))
                                        throw new SQLException("迁移数据校验失败: " + task.id());
                                }
                            }
                        }
                        continue;
                    }
                    if (task.completed()) throw new SQLException("已迁移任务的数据库记录丢失，拒绝回退旧 JSON: " + task.id());
                    writeState(c, task.latest().task(), 0);
                    for (var cp : task.generations()) insertCheckpoint(c, cp);
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO legacy_imports VALUES(?,?)")) {
                        s.setString(1, task.id()); s.setString(2, task.head()); s.executeUpdate();
                    }
                }
                transaction.execute("COMMIT");
            } catch (Exception e) { rollback(c); throw e; }
        } catch (Exception e) { throw failure("旧任务迁移失败，原始 JSON 已保留", e); }
    }

    private static void rollback(Connection c) {
        try (Statement s = c.createStatement()) { s.execute("ROLLBACK"); } catch (SQLException ignored) { }
    }

    private TaskPersistenceException failure(String message, Throwable cause) {
        return new TaskPersistenceException(message + " [" + database + "]"
                + (cause == null ? "" : ": " + cause.getMessage()), cause);
    }
}
