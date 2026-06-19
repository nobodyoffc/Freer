# Bitcoin Schnorr签名详细过程

## 概述

Schnorr签名是比特币在Taproot升级（BIP340）中引入的新签名方案，相比传统的ECDSA签名，它具有更好的性能、更小的签名体积，并支持签名聚合等高级功能。

## 签名范围：对什么内容进行签名？

### 1. 签名覆盖的内容

Schnorr签名**不是仅对输入签名**，而是对整个交易的特定部分进行签名，包括：

- **所有输入**（inputs）
- **所有输出**（outputs）
- **交易的元数据**（版本号、锁定时间等）

这确保了签名者承诺整个交易的内容，防止交易被篡改。

### 2. 为什么要包含输出？

如果只签名输入而不包含输出，攻击者可以：
- 修改接收地址，将资金发送到攻击者地址
- 修改金额分配
- 添加或删除输出

因此，**必须对输出进行签名**以确保交易完整性。

## Schnorr签名的具体步骤

### 第一步：构造签名哈希（Signature Hash）

签名哈希是通过BIP341定义的算法计算的，包含以下元素：

```
SigHash = SHA256(
    hash_type ||              // 签名类型（1字节）
    version ||                // 交易版本（4字节）
    locktime ||               // 锁定时间（4字节）
    sha_prevouts ||           // 所有输入的prevout哈希
    sha_amounts ||            // 所有输入金额的哈希
    sha_scriptpubkeys ||      // 所有输入脚本公钥的哈希
    sha_sequences ||          // 所有输入序列号的哈希
    sha_outputs ||            // 所有输出的哈希
    spend_type ||             // 花费类型
    input_index ||            // 当前输入索引
    ... (更多字段)
)
```

#### 关键组成部分说明：

1. **sha_prevouts**：包含所有输入的引用（txid + vout）
2. **sha_amounts**：包含所有输入的金额
3. **sha_scriptpubkeys**：包含所有输入的锁定脚本
4. **sha_sequences**：包含所有输入的序列号
5. **sha_outputs**：包含所有输出（金额 + 锁定脚本）

### 第二步：Schnorr签名算法

#### 参数准备
- **d**：私钥（32字节标量）
- **P**：公钥 = d·G（G是椭圆曲线基点）
- **m**：消息（即第一步计算的SigHash）

#### 签名过程

1. **生成随机数k**（nonce）：
   ```
   k = H(d || m) mod n
   ```
   其中H是哈希函数，n是曲线阶数

2. **计算R点**：
   ```
   R = k·G
   ```

3. **计算挑战值e**：
   ```
   e = H(R.x || P || m) mod n
   ```
   其中R.x是R点的x坐标

4. **计算签名值s**：
   ```
   s = k + e·d mod n
   ```

5. **输出签名**：
   ```
   signature = (R.x, s)
   ```
   - R.x：32字节（R点的x坐标）
   - s：32字节（签名标量）
   - **总共64字节**（比ECDSA的71-72字节更短）

### 第三步：验证签名

验证者使用公钥P、消息m和签名(R.x, s)进行验证：

1. **重构R点**：
   从R.x和y坐标的偶数性恢复完整的R点

2. **计算挑战值e**：
   ```
   e = H(R.x || P || m) mod n
   ```

3. **验证等式**：
   ```
   s·G == R + e·P
   ```

   如果等式成立，签名有效。

#### 验证原理：
```
s·G = (k + e·d)·G         [根据签名计算公式]
    = k·G + e·d·G         [分配律]
    = R + e·P             [因为 R = k·G, P = d·G]
```

## 签名类型（SigHash Types）

比特币支持多种签名类型，控制签名覆盖的范围：

| 签名类型 | 值 | 说明 |
|---------|---|------|
| SIGHASH_ALL | 0x01 | 签名所有输入和所有输出（最常用） |
| SIGHASH_NONE | 0x02 | 签名所有输入，但不签名任何输出 |
| SIGHASH_SINGLE | 0x03 | 签名所有输入和对应索引的输出 |
| SIGHASH_ANYONECANPAY | 0x80 | 只签名当前输入（可与上述组合） |

### 常见组合：
- **SIGHASH_ALL**（默认）：最安全，签名整个交易
- **SIGHASH_ALL | ANYONECANPAY**：允许他人添加输入（众筹场景）
- **SIGHASH_NONE | ANYONECANPAY**：签名者提供资金但不关心输出

## Taproot中的Schnorr签名特点

### 1. BIP340规范
- 使用secp256k1椭圆曲线
- 公钥和签名都是固定长度（32字节公钥，64字节签名）
- 确定性nonce生成（RFC6979）

### 2. 签名聚合
多个签名可以聚合为一个签名，节省空间：
```
签名1 + 签名2 + ... + 签名n = 聚合签名
```

### 3. 密钥路径花费（Key Path Spend）
在Taproot输出中，可以直接使用单个Schnorr签名花费，无需暴露脚本：
```
witness = <schnorr_signature>
```

### 4. 脚本路径花费（Script Path Spend）
如果使用脚本路径，需要提供脚本和Merkle证明：
```
witness = <signature> <script> <control_block>
```

## 代码实现示例（伪代码）

```java
// 第一步：构造签名哈希
byte[] computeSigHash(Transaction tx, int inputIndex, int sigHashType) {
    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

    // 添加签名类型
    sha256.update(sigHashType);

    // 添加交易版本
    sha256.update(intToBytes(tx.version));

    // 添加锁定时间
    sha256.update(intToBytes(tx.locktime));

    // 计算并添加所有prevouts的哈希
    byte[] prevoutsHash = hashPrevouts(tx.inputs);
    sha256.update(prevoutsHash);

    // 计算并添加所有金额的哈希
    byte[] amountsHash = hashAmounts(tx.inputs);
    sha256.update(amountsHash);

    // 计算并添加所有scriptPubKeys的哈希
    byte[] scriptPubKeysHash = hashScriptPubKeys(tx.inputs);
    sha256.update(scriptPubKeysHash);

    // 计算并添加所有序列号的哈希
    byte[] sequencesHash = hashSequences(tx.inputs);
    sha256.update(sequencesHash);

    // 计算并添加所有输出的哈希
    byte[] outputsHash = hashOutputs(tx.outputs);
    sha256.update(outputsHash);

    // 添加当前输入索引
    sha256.update(intToBytes(inputIndex));

    return sha256.digest();
}

// 第二步：生成Schnorr签名
byte[] schnorrSign(byte[] privateKey, byte[] message) {
    // 1. 生成nonce
    byte[] k = generateNonce(privateKey, message);

    // 2. 计算R = k·G
    ECPoint R = secp256k1.multiply(k);

    // 3. 计算挑战值 e = H(R.x || P || m)
    ECPoint P = secp256k1.multiply(privateKey); // 公钥
    byte[] e = hashChallenge(R.x, P, message);

    // 4. 计算 s = k + e·d
    BigInteger s = k.add(e.multiply(privateKey)).mod(secp256k1.n);

    // 5. 返回签名 (R.x, s)
    return concat(R.x, s);
}

// 第三步：验证Schnorr签名
boolean schnorrVerify(byte[] publicKey, byte[] message, byte[] signature) {
    // 解析签名
    byte[] rx = signature[0:32];
    byte[] s = signature[32:64];

    // 重构R点
    ECPoint R = reconstructR(rx);

    // 计算挑战值
    byte[] e = hashChallenge(rx, publicKey, message);

    // 验证 s·G == R + e·P
    ECPoint left = secp256k1.multiply(s);
    ECPoint right = R.add(secp256k1.multiply(publicKey, e));

    return left.equals(right);
}
```

## 与ECDSA的比较

| 特性 | Schnorr | ECDSA |
|-----|---------|-------|
| 签名大小 | 64字节（固定） | 71-72字节（可变） |
| 验证效率 | 更快 | 较慢 |
| 签名聚合 | 支持 | 不支持 |
| 批量验证 | 支持 | 不支持 |
| 可证明安全性 | 是 | 否 |
| 线性特性 | 是 | 否 |

## 安全性考虑

### 1. Nonce重用风险
如果两次签名使用相同的k值，私钥会被泄露：
```
s1 = k + e1·d
s2 = k + e2·d
=> d = (s1 - s2) / (e1 - e2)
```

因此必须使用**确定性nonce生成**（BIP340规定）。

### 2. 侧信道攻击
实现时需要使用常量时间算法，防止时序攻击。

### 3. 随机数质量
nonce生成需要高质量的随机源和确定性派生。

## 实际应用场景

### 1. 普通P2TR支付
```
输出脚本：OP_1 <32-byte-pubkey>
花费见证：<64-byte-schnorr-signature>
```

### 2. 多签聚合（MuSig2）
多个参与者可以聚合公钥和签名，对外只显示一个签名。

### 3. 闪电网络
Taproot + Schnorr改进了闪电网络的隐私和效率。

### 4. 跨链原子交换
简化的签名机制更适合复杂的智能合约。

## 参考资料

- **BIP340**：Schnorr Signatures for secp256k1
- **BIP341**：Taproot: SegWit version 1 spending rules
- **BIP342**：Validation of Taproot Scripts
- **BIP86**：Key Derivation for Single Key P2TR Outputs

## 总结

比特币Schnorr签名的关键点：

1. ✅ **签名覆盖整个交易**：包括所有输入和所有输出
2. ✅ **签名哈希构造**：通过BIP341定义的算法计算
3. ✅ **三步过程**：计算SigHash → 生成签名 → 验证签名
4. ✅ **固定大小**：64字节签名，比ECDSA更紧凑
5. ✅ **支持聚合**：多个签名可以合并为一个
6. ✅ **确定性nonce**：防止密钥泄露

Schnorr签名是比特币隐私和可扩展性的重要改进，为更复杂的协议（如MuSig、跨输入聚合、适配器签名）奠定了基础。
