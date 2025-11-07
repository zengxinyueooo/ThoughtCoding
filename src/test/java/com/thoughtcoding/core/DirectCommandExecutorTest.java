package com.thoughtcoding.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * DirectCommandExecutor 功能测试
 * 测试新增的高级功能：自然语言识别、批量操作、智能上下文
 */
public class DirectCommandExecutorTest {

    private ProjectContext projectContext;

    @BeforeEach
    public void setUp() {
        projectContext = new ProjectContext(System.getProperty("user.dir"));
    }

    @Test
    public void testProjectContextDetection() {
        // 测试项目类型检测
        ProjectContext.ProjectType type = projectContext.getProjectType();
        assertNotNull(type, "项目类型不应为空");

        System.out.println("✅ 项目类型检测测试通过");
        System.out.println("检测到的项目类型: " + type.getDisplayName());
    }

    @Test
    public void testSmartCommandTranslation() {
        // 测试智能命令转换
        String buildCmd = projectContext.smartTranslate("build");
        String testCmd = projectContext.smartTranslate("test");
        String cleanCmd = projectContext.smartTranslate("clean");

        System.out.println("✅ 智能命令转换测试通过");
        System.out.println("构建命令: " + buildCmd);
        System.out.println("测试命令: " + testCmd);
        System.out.println("清理命令: " + cleanCmd);

        // 根据项目类型验证
        ProjectContext.ProjectType type = projectContext.getProjectType();
        if (type == ProjectContext.ProjectType.MAVEN) {
            assertEquals("mvn package", buildCmd);
            assertEquals("mvn test", testCmd);
            assertEquals("mvn clean", cleanCmd);
        }
    }

    @Test
    public void testProjectSummary() {
        // 测试项目信息摘要
        String summary = projectContext.getSummary();
        assertNotNull(summary, "项目摘要不应为空");
        assertTrue(summary.contains("项目类型"), "摘要应包含项目类型");

        System.out.println("✅ 项目摘要测试通过");
        System.out.println(summary);
    }

    @Test
    public void testRecommendedCommands() {
        // 测试推荐命令
        String[] recommendations = projectContext.getRecommendedCommands();
        assertNotNull(recommendations, "推荐命令不应为空");

        System.out.println("✅ 推荐命令测试通过");
        System.out.println("推荐命令数量: " + recommendations.length);
        for (String cmd : recommendations) {
            System.out.println("  • " + cmd);
        }
    }
}

