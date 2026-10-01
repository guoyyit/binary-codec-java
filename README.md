# binary-codec-java

模式驱动的二进制消息编解码引擎。消息结构由模式声明文本定义，编码结果用于节点间传输，
新旧版本模式可以共存演进。编解码核心逻辑全部在本仓库内实现（`codec` 包），不依赖任何
现成的序列化框架。

## 快速开始

```java
SchemaRegistry registry = new SchemaRegistry();
registry.register("""
        message Person {
            int32 id = 1;
            optional string name = 2 [default = "anon"];
        }
        """);

Codec codec = new Codec(registry);
MessageValue value = codec.newMessage("Person").set("id", 42L);
byte[] bytes = codec.encode(value);
MessageValue decoded = codec.decode("Person", bytes);
```

## 模式声明语法

```
message Person {
    int32 id = 1;                          // 整数：int32 / int64
    bool active = 2;                       // 布尔
    double ratio = 3;                      // 浮点：float / double
    bytes avatar = 4;                      // 字节串
    optional string name = 5 [default = "anon"];   // 字符串，可带模式默认值
    repeated int64 scores = 6;             // 重复字段
    map<string, int32> attrs = 7;          // 映射字段（键：int32/int64/bool/string）
    Address addr = 8;                      // 嵌套消息
    Kind kind = 9 [default = USER];        // 枚举（默认值为常量名）
    oneof contact {                        // 互斥联合：最多一个成员被设置
        string email = 10;
        string phone = 11;
    }
    reserved 100 to 199, 300;              // 保留编号区间
}

enum Kind {
    USER = 0;
    ADMIN = 1;
}
```

规则说明：

- 注释支持 `#` 与 `//`；字段编号范围为 `[1, 2^29 - 1]`。
- `optional` 标记的字段可区分"显式设置为默认值"与"未设置"（`MessageValue.has`）；
  未设置字段在编码时被省略。未标记 `optional` 的字段同样按"未设置即省略"编码，
  `optional` 用于表达调用方对设置状态的关注意图。
- 默认值子句 `[default = ...]` 仅允许用于标量与枚举字段；重复字段与映射字段不可声明。
- `reserved` 声明的编号区间内的编号不得再被字段使用。
- 注册阶段即拒绝：编号冲突、重复定义、引用不存在的类型、复用保留编号，
  错误信息携带声明行号（`SchemaException.getLine()`）。
- 同一注册表内消息与枚举共享命名空间；热注册不允许覆盖已注册类型。

## 编码格式

每个字段编码为 `tag + value`：

- **tag**：`(fieldNumber << 3) | wireType` 的变长整数（varint）。
- **wireType**：`0`=VARINT，`1`=FIXED64，`2`=LEN，`5`=FIXED32。

| 类型 | wireType | 编码 |
| --- | --- | --- |
| int32 / int64 / 枚举 | 0 | zigzag 变长（负数与小值均不膨胀，\|v\|<64 仅 1 字节） |
| bool | 0 | 单字节 0/1 |
| double | 1 | 8 字节小端定长 |
| float | 5 | 4 字节小端定长 |
| string / bytes | 2 | varint 长度 + 原始字节（string 为 UTF-8） |
| 嵌套消息 | 2 | varint 长度 + 消息字节 |
| 标量重复字段 | 2 | packed：varint 总长度 + 元素连续存储 |
| 字符串/消息重复字段 | 2 | 逐元素 `tag + value` |
| 映射字段 | 2 | 重复条目，条目内键为字段 1、值为字段 2 |

**确定性**：同一消息在同一模式下任意次编码得到逐字节相同的输出。字段按编号升序写出，
映射条目按键排序（布尔 < 整数 < 字符串，同类型按自然序），未知字段按解码时顺序
附加在已知字段之后。

## 版本兼容规则

- **新端读旧端**：缺失字段不回填到数据中，读取时取模式默认值（`[default = ...]`），
  未声明默认值时取类型固有默认值（0、false、空串、空列表、空映射）。
- **旧端读新端**：不认识的字段被跳过并完整保留为未知字段（含原始线格式字节），
  再次编码时按原样写回，多轮转发不丢失。
- **未知枚举值**：旧端收到不认识的枚举值时按未知字段保留，回写后新端仍可读出。
- **oneof**：解码时后出现的成员覆盖先出现的成员；设置成员会清除同组其他成员。
- 字段编号一旦发布不得更改含义或复用；废弃编号应加入 `reserved` 防止误用。
- 同一字段编号在不同版本间改变线类型（如 int32 改 string）属于不兼容变更，
  解码端会按"非法字段编码标记"拒绝。

## 解码上限

由 `CodecLimits` 配置，超出即拒绝并抛出带字节位置的 `CodecException`：

| 上限 | 默认值 | 含义 |
| --- | --- | --- |
| `maxMessageBytes` | 4 MiB | 输入总字节数及单个长度前缀的上限 |
| `maxDepth` | 100 | 嵌套消息最大深度 |
| `maxStringBytes` | 1 MiB | 单个字符串字段最大字节数 |
| `maxContainerElements` | 65536 | 整个输入中重复/映射元素总数 |

内存占用受双重约束：长度声明先与配置上限比较，再与剩余实际输入比较，任一不满足即拒绝；
截断输入、非法 wire type、零字段号、varint 溢出、非法 UTF-8 同样被拒绝，
拒绝时不产生部分结果。

## 并发模型

- `SchemaRegistry` 支持并发读取与安全的热注册：注册在内部串行化，校验通过后
  以不可变快照整体发布；注册失败不影响已生效模式。
- `Codec` / `BinaryEncoder` / `BinaryDecoder` 无可变内部状态，可被多线程共享；
  并发编码同一消息与单线程结果逐字节一致，并发解码互不干扰。
- `MessageValue` 是可变对象，不应在线程间共享；每个线程使用独立实例。

## 新旧版本共存迁移示例

旧模式 `person.v1`：

```
message Person {
    int32 id = 1;
    optional string name = 2;
}
```

演进后的 `person.v2`（新增字段、废弃旧字段）：

```
message Person {
    int32 id = 1;
    optional string name = 2;
    optional int32 age = 3 [default = 18];   // 新增：旧端跳过并保留，新端缺失时取 18
    reserved 4 to 5;                          // 废弃编号保留，防止误用
}
```

迁移步骤：

1. 只新增字段、不复用编号地发布 v2；新节点用 v2 注册自己的 `SchemaRegistry`。
2. 旧节点无需升级即可继续通信：v2 写入的新字段被旧节点跳过并原样保留，
   转发到另一个 v2 节点时新字段仍然完整。
3. v2 节点读取 v1 写入的消息时，`age` 未设置（`has("age") == false`），
   `get("age")` 返回模式默认值 `18`。
4. 全部节点升级后，若某字段确认废弃，将其编号加入 `reserved`，
   注册表会拒绝任何复用该编号的新声明。

## 构建与测试

```bash
mvn test
```

测试覆盖：跨版本读写、未知字段回写、显式默认值与缺失字段区分、损坏输入拒绝、
并发编解码与编码确定性（`src/test/java/com/github/guoyyit/binarycodecjava/tests/`）。
