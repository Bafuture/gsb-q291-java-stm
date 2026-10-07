# 软件事务内存组件

Pair-wise GSB 标注任务仓库（第 18 批 / 291）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 实现说明

核心代码位于 `src/main/java/com/example/gsb/stm/`：

| 类型 | 职责 |
|------|------|
| `Stm` | 运行时入口：`newRef` 创建事务引用，`atomic` / `atomicReadOnly` 执行原子块，冲突自动重试，`stats()` 输出统计 |
| `StmRef<T>` | 事务引用，读写必须发生在原子块内，写入先缓冲在事务日志（last write wins），提交时才发布 |
| `Transaction` | 单次尝试的事务日志：读集（ref → 读到的版本）与写集（ref → 缓冲值） |
| `StmConfig` | 重试上限：`maxRetries`（最大重试次数）与 `maxRetryDuration`（最大总时长） |
| `StmConflictException` | 提交时读集校验失败：指出具体引用、期望/实际版本、改写者事务 id |
| `StmRetryExhaustedException` | 重试超限后的失败异常，携带最后一次冲突详情 |
| `StmStats` | 统计快照：事务数、提交数、冲突重试次数、平均重试轮数 |

提交协议：按引用 id 排序后锁住整个写集（避免死锁），重新校验读集版本，
再把本次写集以同一个新版本号原子发布；只读事务只校验、不加锁、不写，
因此只读事务之间互不冲突。

```java
Stm stm = new Stm(new StmConfig(100, Duration.ofSeconds(10)));
StmRef<Integer> alice = stm.newRef("alice", 100);
StmRef<Integer> bob = stm.newRef("bob", 50);

stm.atomic(() -> {
    alice.set(alice.get() - 30);
    bob.set(bob.get() + 30);
});

int total = stm.atomicReadOnly(() -> alice.get() + bob.get());
StmStats stats = stm.stats();
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

我们要在内存里做多变量原子修改，用锁拼起来容易死锁也难组合。请从零实现一个软件事务内存（STM）组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 提供事务引用（可读写）与原子块入口，块内的读写都记录在事务日志里；2) 提交时做乐观冲突检测：读集内任一引用在事务期间被他人写过即判定冲突；3) 冲突时整个原子块自动重试，并限制重试次数与总时长，超限抛出明确的失败异常；4) 事务内对同一引用的多次写只保留最后一次，回滚时不得影响其它事务的已提交修改；5) 支持只读事务：只读块不产生写冲突，需有测试证明两个只读事务可并发通过；6) 冲突检测要落实到具体引用：失败信息需指出是哪个引用、被哪个事务改写；7) 提供统计：事务数、提交数、冲突重试次数与平均重试轮数；8) 测试覆盖原子提交、冲突重试、只读并发、重试上限、写合并与统计；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。
