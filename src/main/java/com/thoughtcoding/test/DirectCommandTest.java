package com.thoughtcoding.test;

import com.thoughtcoding.core.DirectCommandExecutor;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.ui.ThoughtCodingUI;

/**
 * 直接命令执行功能测试类
 */
public class DirectCommandTest {

    public static void main(String[] args) {
        System.out.println("🧪 测试直接命令执行功能");

        try {
            // 初始化上下文
            ThoughtCodingContext context = ThoughtCodingContext.initialize();
            DirectCommandExecutor executor = new DirectCommandExecutor(context);

            // 测试命令列表
            String[] testCommands = {
                "java -version",
                "git status",
                "pwd",
                "whoami",
                "ls -la",
                "date",
                "这是一个普通的问题，应该交给AI处理"
            };

            System.out.println("\n📋 测试命令识别:");
            for (String cmd : testCommands) {
                boolean shouldExecute = executor.shouldExecuteDirectly(cmd);
                System.out.printf("  %-40s → %s\n", "\"" + cmd + "\"", shouldExecute ? "直接执行" : "AI处理");
            }

            System.out.println("\n🔧 显示支持的所有直接命令:");
            executor.listSupportedCommands();

            System.out.println("\n✅ 测试完成!");

        } catch (Exception e) {
            System.err.println("❌ 测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
