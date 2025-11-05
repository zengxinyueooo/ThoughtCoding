#!/bin/bash

# ThoughtCoding CLI 功能测试脚本
# 测试所有新增的高级功能

echo "🚀 ThoughtCoding CLI - 功能测试"
echo "================================"
echo ""

# 设置颜色
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 测试计数
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# 测试函数
test_command() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -e "${YELLOW}测试 $TOTAL_TESTS: $1${NC}"
    echo "命令: $2"
    echo ""

    # 这里只是模拟测试，实际需要运行 CLI
    echo "$2" | java -jar target/thoughtcoding.jar -p "$2" 2>&1

    if [ $? -eq 0 ]; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        echo -e "${GREEN}✅ 通过${NC}"
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
        echo -e "${RED}❌ 失败${NC}"
    fi
    echo ""
    echo "---"
    echo ""
}

echo "📋 测试类别："
echo "1. 基础命令执行"
echo "2. 自然语言识别"
echo "3. 智能上下文"
echo "4. 项目信息"
echo ""
echo "开始测试..."
echo ""

# ============ 1. 基础命令测试 ============
echo "📦 1. 基础命令执行测试"
echo "================================"

# 检查 JAR 文件是否存在
if [ ! -f "target/thoughtcoding.jar" ]; then
    echo -e "${RED}❌ 错误: target/thoughtcoding.jar 不存在${NC}"
    echo "请先运行 mvn package 构建项目"
    exit 1
fi

# 测试 1: 查看 Java 版本
test_command "查看 Java 版本" "java version"

# 测试 2: 查看当前目录
test_command "查看当前目录" "当前目录"

# 测试 3: 查看文件列表
test_command "查看文件列表" "查看文件"

# ============ 2. 自然语言测试 ============
echo "🗣️  2. 自然语言识别测试"
echo "================================"

# 测试 4: Git 状态
test_command "Git 状态查询" "查看git状态"

# 测试 5: Maven 编译
test_command "Maven 编译" "maven编译"

# ============ 3. 智能上下文测试 ============
echo "🧠 3. 智能上下文测试"
echo "================================"

# 测试 6: 项目信息
test_command "项目信息查询" "项目信息"

# 测试 7: 推荐命令
test_command "推荐命令查询" "推荐命令"

# ============ 测试总结 ============
echo ""
echo "================================"
echo "📊 测试总结"
echo "================================"
echo -e "总测试数: ${TOTAL_TESTS}"
echo -e "${GREEN}通过: ${PASSED_TESTS}${NC}"
echo -e "${RED}失败: ${FAILED_TESTS}${NC}"
echo ""

if [ $FAILED_TESTS -eq 0 ]; then
    echo -e "${GREEN}🎉 所有测试通过！${NC}"
    exit 0
else
    echo -e "${RED}⚠️  有 ${FAILED_TESTS} 个测试失败${NC}"
    exit 1
fi

