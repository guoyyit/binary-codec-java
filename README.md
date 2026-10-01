# binary-codec-java

模式驱动的二进制消息编解码引擎。消息结构由模式声明文本定义，编码结果用于节点间传输；
核心编解码逻辑全部在本仓库内实现（`com.github.guoyyit.binarycodecjava`），
不依赖任何现成序列化框架。

## 目录

- [快速上手](#快速上手)
- [模式声明语法](#模式声明语法)
- [编码格式](#编码格式)
- [版本兼容规则](#版本兼容规则)
- [各项上限](#各项上限)
- [新旧版本共存迁移示例](#新旧版本共存迁移示例)
- [并发模型](#并发模型)
- [错误模型](#错误模型)

## 快速上手

```java
SchemaRegistry registry = new SchemaRegistry();
registry.register("""
        schema shop;
        version "1.0";

        enum Status {
            OFFLINE = 0;
            ONLINE = 1;
        }

        message Product {
            int64 id = 1;
            string name = 2;
            Status status = 3;
            repeated string tags = 4;
            map<string, string> labels = 5;

            oneof identifier {
                string sku = 10;
                int64 ean13 = 11;
            }
        }
        """);

Codec codec = new Codec(registry);

MessageValue product = codec.newMessage("shop", "Product");
product.setField("id", 42L);
product.setField("name", "widget");
product.setField("status", "ONLINE");
product.setField("tags", new ArrayList<>(List.of("new", "sale")));
product.setField("sku", "WGT-001");

byte[] wire = codec.encode(product);

// 解码端（默认使用该 schema 的最新已注册版本）
MessageValue decoded = codec.decode(wire, "shop", "Product");

// 也可以显式指定版本，用于旧版本节点视图
MessageValue oldView = codec.decode(wire, "shop", "1.0", "Product");
```

### 字段类型与 Java 类型映射

| 模式类型 | Java 类型 |
| --- | --- |
| `int32` | `Integer` |
| `int64` | `Long` |
| `bool` | `Boolean` |
| `float` | `Float` |
| `double` | `Double` |
| `bytes` | `byte[]` |
| `string` | `String`（UTF-8） |
| 枚举 | `String`（枚举值名）；无法识别的编号回退为 `Long` |
| 嵌套消息 | `MessageValue` |
| `repeated T` | `List<T>` |
| `map<K,V>` | `Map<K,V>`（输入顺序任意，编码按键排序） |

## 模式声明语法

```text
schema <名称> ;
version "<版本字符串>" ;

[message|enum 定义...]
```

### 标量类型

`int32`、`int64`、`bool`、`float`、`double`、`bytes`、`string`。

### 消息

```text
message SearchRequest {
    string query = 1;
    int32 page = 2;
    repeated string keywords = 3;
    map<string, string> metadata = 4;

    oneof target {
        string user_id = 10;
        int64 device_id = 11;
    }

    reserved 20 to 30, 99;
    reserved "legacy_field";

    message Nested { int32 x = 1; }
}
```

- 每个字段必须有唯一、稳定的编号；字段名在消息内唯一。
- 编号合法范围为 `1..536870911`（`2^29 - 1`）。
- 字段标签：默认单数字段；`repeated` 重复字段；`map<K,V>` 映射字段。
  - map 的键类型只能是整数、`bool`、`string`。
  - map 的值类型可以是标量、枚举或嵌套消息（不能是 `map`）。
  - `oneof` 内只能是单数字段，不能放 `repeated`/`map`；同一 oneof 同时只允许一个成员被设置。
- 嵌套定义：消息与枚举可以任意层级嵌套，类型引用先在当前作用域、再向外层解析。

### 枚举

```text
enum Color {
    UNKNOWN = 0;   // 存在编号 0 时它是默认值；否则第一个声明的值为默认值
    RED = 1;
    GREEN = 2;
}
```

枚举值名与编号都不得重复；空枚举会被拒绝。

### 保留编号与保留名

```text
reserved 5;
reserved 10 to 20;
reserved 100 to max;
reserved "old_name";
```

新字段复用被保留的编号或名称，会在注册阶段被拒绝。

### 注释

支持 `//` 行注释与 `/* ... */` 块注释。

### 注册期校验（全部给出声明行号）

- 字段编号冲突、字段名重复
- 消息/枚举重复定义
- 引用不存在的类型
- 复用保留编号 / 保留名
- 枚举值名或编号重复
- 非法 map 键类型、oneof 内出现重复/映射字段
- 字段编号越界、保留区间非法、词法/语法错误

错误抛出 `SchemaException`，`line()` 可定位到声明行。

## 编码格式

采用自描述字段标签（Tag-Length-Value 风格），字段增删不影响其他字段解析。

### 标签

每个字段前是一个无符号变长整数标签：

```
tag = (field_number << 3) | wire_type
```

| wire type | 含义 | 载荷 |
| --- | --- | --- |
| `0` VARINT | 变长整数 | 最多 10 字节的 base-128 varint |
| `1` FIXED64 | 定长 8 字节 | `double`，小端序 |
| `2` LENGTH_DELIMITED | varint 长度 + 字节 | `string`、`bytes`、嵌套消息、packed 重复、map 条目 |
| `5` FIXED32 | 定长 4 字节 | `float`，小端序 |

wire type `3`、`4`（组）为非法值，出现即拒绝。

### 整数编码

- 所有整数使用 **ZigZag + base-128 varint**：小正数与高频小值占 1 字节，负数与同量级正数等长，不发生符号位膨胀。
  - `int32`：`zz = (n << 1) ^ (n >> 31)`，取值范围 `-2^31..2^31-1`，超出范围拒绝。
  - `int64`：`zz = (n << 1) ^ (n >> 63)`。
- `bool`：1 字节 varint（`0`/`1`）。
- 枚举：按编号 ZigZag varint。

### 重复字段的紧凑存储

- 数值型重复字段（varint/fixed 族、枚举）统一使用 **packed** 连续存储：
  一个 `LENGTH_DELIMITED` 字段，载荷内紧密排列元素，无逐元素标签。
- 非数值型重复字段（字符串、字节串、嵌套消息）每个元素各带一个标签。

### map 字段

每个键值对编码为一个合成嵌套消息（字段 1 = 键，字段 2 = 值），
条目按键的确定性顺序（数字按数值、字符串按 Unicode 码位、布尔 false<true）排序输出，
保证同一消息任意次编码逐字节相同。

### 可选语义与确定性

- 字段有明确的“是否已设置”状态：未设置的字段（包括默认值）编码时整体省略；
  显式设置为默认值（如 `0`、`""`、`false`）仍会被编码，解码端可区分“显式默认”与“缺失”。
- 消息按字段编号升序输出已知字段；未知字段在已知字段之后按接收顺序原样输出。
- `float`/`double` 按原始位（raw bits）编码。

## 版本兼容规则

### 前向 / 后向兼容

- **新端读旧消息**：新模式新增的字段若缺失，取模式默认值（数值 `0`、布尔 `false`、
  字符串 `""`、字节串空数组、枚举默认值、空列表/空映射、嵌套消息为未设置）。
- **旧端读新消息**：无法识别的字段编号被完整保留为未知字段：
  保留字段号、wire type 与原始载荷字节；再次编码时按原样写回（与收到的字节一致）。
  未知枚举编号按数字保留并原样回写。
- 未知字段的解释被推迟到拥有更新模式的节点，中间节点可安全转发。

### 允许与禁止的演进

允许：新增字段、删除字段（删除后应 `reserved` 其编号/名称）、新增枚举值、
新增嵌套类型、新增 map/repeated/oneof 成员。

禁止：复用已删字段的编号或名称、改变字段 wire type（例如 `int32`→`string`）、
把单数字段改为 repeated/map 或反向修改。解码器发现 wire type 与模式不符会拒绝输入。

### 模式共存

- 注册表按 `(schema 名称, 版本字符串)` 保存模式；同名 schema 的多个版本可以同时注册、同时使用。
- 编码必须基于发送方持有的具体版本（`MessageValue` 绑定具体描述符）；
  解码可用 `decode(data, schema, version, message)` 显式指定版本，或取最新版本。
- 兼容性由线格式保证：模式版本号本身不写入消息体。

## 新旧版本共存迁移示例

版本 1 的节点：

```text
schema users;
version "1";
message User {
    string name = 1;
    int32 level = 2;
}
```

版本 2 的节点新增字段：

```text
schema users;
version "2";
enum Role { GUEST = 0; ADMIN = 1; }
message User {
    string name = 1;
    int32 level = 2;
    string email = 3;
    Role role = 4;
    repeated int32 scores = 5;
}
```

迁移路径：

1. 所有节点先升级解码能力（部署新引擎），但仍可按版本 `1` 生产消息。
2. 新版本节点注册版本 `2` 并开始填充 `email`/`role`/`scores`。
3. 旧版本节点收到这些消息：`name`、`level` 正常读取；字段 3/4/5 进入未知字段并被原样转发或回写。
4. 旧版本节点读取新版本消息时，未携带的新字段表现为未设置/默认值；
   新版本节点读取旧消息时同理。
5. 确认所有节点升级后，才允许生产端使用新字段；旧字段编号永久保留：

```text
message User {
    reserved 6;            // 若以后删除字段
    reserved "temp_name";
    ...
}
```

## 各项上限

上限定义在 `com.github.guoyyit.binarycodecjava.codec.CodecLimits`：

| 上限 | 默认值 | 说明 |
| --- | --- | --- |
| `MAX_INPUT_SIZE` | 64 MiB | 解码输入总大小，解码前检查 |
| `MAX_DECLARED_LENGTH` | 16 MiB | 单个长度声明（字符串/字节串/嵌套消息/packed/map 条目）上限 |
| `MAX_NESTING_DEPTH` | 64 | 嵌套消息（含 map 条目）最大深度 |
| `MAX_FIELD_NUMBER` | 536870911 | 字段编号上限 |
| `MAX_COLLECTION_ELEMENTS` | 10,000,000 | 单个 repeated/map 字段元素/条目数上限 |
| `MAX_TOTAL_VALUES` | 32,000,000 | 单条消息解码过程中允许的总字段值数量上限 |
| varint 长度 | 10 字节 | 超长或溢出立即拒绝 |

内存同时受**输入声明**与**实际内容**约束：长度前缀不能超过剩余字节、
不能超过声明上限；元素数量与总值数量均有硬性上限，避免小输入构造出海量对象。

## 解码拒绝项

以下输入一律拒绝（抛 `CodecException`，`position()` 给出字节位置），且不返回任何部分结果：

- 截断输入（varint/fixed/长度定界内容不完整）
- 非法字段标签（标签 0、字段编号越界、非法 wire type `3`/`4`/其他保留值）
- 声明长度超过剩余字节、超过长度上限、长度为负或超过可寻址范围
- 嵌套深度超过上限、varint 超过 10 字节或 64 位溢出
- 字段 wire type 与模式不匹配
- `int32` 超出 32 位 ZigZag 表示范围
- 非法 UTF-8 字符串
- 集合元素 / 总值数量超限

## 并发模型

- `SchemaRegistry` 基于 `ConcurrentHashMap`：支持并发读取与并发热注册。
- 注册是“先完整解析校验、再原子发布”：注册失败不会产生任何部分状态，已生效的模式不受影响。
- `Codec`、`BinaryEncoder`、`BinaryDecoder` 无共享可变状态，可被多线程长期共享；
  每次编码使用独立缓冲，每次解码创建独立的 `MessageValue` 树，互不干扰。
- 对同一不可变 `MessageValue` 的并发编码与单线程结果逐字节一致（map 按键排序、字段按编号排序）。

## 错误模型

- `SchemaException`：模式注册阶段错误，`line()` 为声明行号。
- `CodecException`：编解码阶段错误，`position()` 为相关字节位置。

## 测试

```bash
mvn test
```

测试覆盖：全类型 round-trip、跨版本读写、未知字段逐字节回写、显式默认值与缺失区分、
损坏输入拒绝（截断/非法标签/越界长度/深度/溢出/非法 UTF-8）、并发编解码与热注册、编码确定性。
