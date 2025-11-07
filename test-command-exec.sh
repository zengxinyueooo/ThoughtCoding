#!/bin/bash

# 测试命令执行功能

echo "🧪 测试 ThoughtCoding 命令执行功能"
echo "=================================="

cd /Users/zengxinyue/Desktop/仓库/ThoughtCoding

echo ""
echo "1️⃣  测试简单命令（应该成功）"
echo "输入: pwd"
echo "pwd" | java -jar target/thoughtcoding.jar -p "pwd"

echo ""
echo "2️⃣  测试 Maven 命令（检查是否失败）"
echo "输入: 构建"
echo "构建" | java -jar target/thoughtcoding.jar -p "构建"

echo ""
echo "3️⃣  测试 Git 命令（应该成功）"
echo "输入: git status"
java -jar target/thoughtcoding.jar -p "git status"

echo ""
echo "✅ 测试完成"

