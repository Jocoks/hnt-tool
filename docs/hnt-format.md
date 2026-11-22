# `.hnt` 格式与密钥链路

## 容器

| 偏移 | 长度 | 内容 |
| --- | --- | --- |
| 0 | 4 | `jsonLen`（小端 u32） |
| 4 | 4 | `encLen` = 12 + 密文长度 |
| 8 | 4 | `hashLen` = 64 |
| 12 | jsonLen | JSON：`{"hitopId":"...","version":"..."}` |
| 12+jsonLen | 12 | AES-GCM IV |
| ... | encLen-12 | 密文（末尾 16 字节是 GCM tag） |
| 末尾 | hashLen | ASCII：`sha256(JSON‖IV‖密文)` 的 hex |

解密后得到普通 ZIP（`.hwt`），包含 `description.xml`、`preview/`、`wallpaper/`、`unlock/`、
`icons/`、`com.hihonor.android.launcher/` 等。

## 解密算法

`CryptTools.decryptFileByAESStream`：

```
读 8 字节: [u32 offsetHeader][u32 length]
offset = offsetHeader + 12
IV     = data[offset .. offset+12]
密文   = data[offset+12 .. offsetHeader + length]
AES/GCM/NoPadding, tag 128 bit
```

## 内容密钥

```
files/<sha256hex(hitopId)>    // 内容是 hex 文本：12B IV + AES-GCM(keystoreKey) 密文
plaintext = AES-GCM-decrypt(AndroidKeyStore[alias = hitopId], 上述密文)
key       = new String(plaintext, UTF-8).getBytes(ISO_8859_1)   // 16 字节
```

`keystore` 密钥受 uid + tee 保护，无法导出，只能在该 App 进程里使用。

## 重打包

1. 目录 → ZIP（DEFLATED）
2. 随机 12B IV，`AES/GCM` 加密 ZIP（含 tag）
3. `encLen = 12 + ct.length`
4. 头 = `[jsonLen][encLen][64]`，尾部 = `sha256(JSON‖IV‖密文)` 的 64 位 hex

## 让主题管理器识别（可选）

`我的下载 → 主题` 的列表来自 ContentProvider（`content://com.hihonor.android.thememanager.ContentProvider/themeInfo`）
再经 `themeRebuild()` 用 `isFileExists(package_path)` 过滤，因此还需要：

- `Honor/Themes/hnt_<hitopId>.hwt`（解锁版 ZIP）
- `Honor/Themes/.cache/hnt_<hitopId>.hwt/{description.xml,preview/}`
- （本工具的做法）在 provider 查询结果里合成一行 `type=33 / package_type=3 / spare_one=internal`
