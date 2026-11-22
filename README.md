# hnt-tool — 荣耀主题包 `.hnt` 拆包 / 打包工具

一个 Android 小工具 + LSPosed 模块，用来解开荣耀（Honor）主题管理器的 `.hnt` 主题包，
把它还原成普通的 ZIP（`.hwt`）目录结构，也能把改好的目录重新加密打包回 `.hnt`。

> 仅用于**自己设备上、自己拥有的主题包**的备份、修改与学习。请勿分发他人付费主题。

> ## ⚠️ 传播约定（请先读）
>
> - 本项目**仅供个人在自己设备上备份、修改自己拥有的主题包，以及学习文件格式**。
> - **禁止在国内社交平台宣传、推荐、引流本项目**，包括但不限于：B 站、抖音、快手、贴吧、酷安、小红书、微博、知乎、微信公众号/视频号。
> - 请勿用它传播他人的付费主题；请勿打包售卖。
> - 转载/引用请保留本段与 [LICENSE](LICENSE)，并同样遵守上面的约定。

## 功能

- **拆包**：选一个 `.hnt` → 解密 → 解到 `/storage/emulated/0/Download/HNT/<hitopId>/`
- **打包**：选 `Download/HNT` 下的目录 → 打 ZIP → AES-GCM 加密 → 写回 `.hnt`
- **取密钥**（两种方式）
  - LSPosed 模块：在主题管理器进程里调用它自己的 `AndroidKeyStore`，解出该 `hitopId` 对应的 16 字节内容密钥（免 root）
  - 手动输入：知道密钥 hex 时直接填
- 打包后会自动同步一份「解锁版」到 `Honor/Themes/hnt_<hitopId>.hwt`（含预览缓存与标题），
  让主题管理器「我的下载」能直接识别并应用

## 文件格式（实测）

```
+0   u32 jsonLen      // 41
+4   u32 encLen       // 12 + 密文长度（不是文件总长）
+8   u32 hashLen      // 64
+12  JSON             // {"hitopId":"101113191","version":"8.0.1"}
+..  12B IV
+..  AES-GCM 密文（末 16B 为 tag）
末尾 64 个 ASCII hex = sha256(JSON + IV + 密文)
```

细节见 [docs/hnt-format.md](docs/hnt-format.md)。

## 构建

需要 JDK、Android SDK（build-tools 里的 `d8`、`zipalign`、`apksigner`、`aapt2`）。

```bash
# 改 build.sh 顶部的路径后
./tools/build.sh
# 产物：HNTTool-1.0-unsigned.apk（未签名）
```

安装前用你自己的 key 签名：

```bash
apksigner sign --ks your.jks --out signed.apk HNTTool-1.0-unsigned.apk
adb install -r signed.apk
```

## 使用

1. 安装并打开，第一次会请求「所有文件访问权限」（写 `Download/HNT` 用）
2. 「拆包」：选 `.hnt`（比如 `Honor/xxxx.hnt`）→ 点「开始拆包」
3. 改 `Download/HNT/<id>/` 下的文件
4. 「打包」：选中该目录 → 点「打包并保存」
5. 想让它出现在主题管理器「我的下载」：装 LSPosed 模块版，作用域勾选
   `com.hihonor.android.thememanager`，然后打包时会自动生成解锁版与预览缓存

## 取密钥是怎么实现的

主题管理器把每个主题的内容密钥存在 `AndroidKeyStore`（alias = `hitopId`），
密文落在 `files/<sha256(hitopId)>`（hex）。模块在该进程内：

1. 反射装配 `android.security.keystore2.AndroidKeyStoreProvider`
2. `KeyStore.getKey(alias)` 拿到 keystore 里的 AES key
3. 解开 `files/<sha256(hitopId)>` → 明文按 UTF-8 解码再按 ISO-8859-1 取字节 → 16 字节内容密钥
4. 写一份到 `Download/HNT/.hntkeys/<hitopId>.hex` 并广播回 App

## 已知限制

- 不同主题的 `hitopId` 不同，密钥也各自独立；换包要重新取
- 荣耀锁屏引擎的行为：主题包一旦带自定义锁屏 `unlock/lockscreen/manifest.xml`，
  整块锁屏由它绘制，系统杂志时钟不会叠加显示
- 打包出的 `.hnt` 用相同 `hitopId` 与密钥，AES-GCM 每次随机 IV，所以体积/字节与原包不同属正常

## License

MIT
