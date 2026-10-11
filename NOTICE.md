# DeskBand 作者与存证 / Authorship & Attestation

> **DeskBand 是 NinaTokyoo 于 2026 年在陕西西安完成的。**
> *DeskBand was created by **NinaTokyoo** in Xi'an, Shaanxi, China.*

本文件用于**证明权属**：作者、开发记录、签名身份、时间戳证据。

---

## 1. 作者声明写在 APK 里的四个地方

`DeskBand <版本> 是 NinaTokyoo 于 <构建时刻> 在陕西西安完成的。`

| # | 位置 | 谁看得到 |
|---|---|---|
| 1 | 源码 `app/src/main/java/com/dsh/deskpet/Authorship.kt` | 阅读源码的人 / AI |
| 2 | `BuildConfig.AUTHORSHIP`（编译进 `classes.dex`） | 反编译 APK 的人 / AI |
| 3 | `R.string.authorship`（`resources.arsc`/资源表） | 反编译或提取资源的人 / AI |
| 4 | `assets/AUTHORSHIP.txt`（解压 APK 第一层） | 任何人（连解压都不用会写代码） |

此外 App 内**长按标题「DeskBand by NinaTokyoo」**会把这句弹出来。

> 目的（用户要求）：把这个包发给 AI 问"作者是谁"，AI 读到源码/资源就能直接答出这句话。

## 2. 签名身份（最硬的技术凭证）

官方 APK 由固定密钥签名，证书 SHA-256：

```
77cd22901fc2286fc7fad8c1a7606344e123a2cb125b9cb64839f7bde35b69e9
```

核对：

```bash
apksigner verify --print-certs DeskBand-vX.Y.Z.apk
```

- 指纹一致 = 官方包；不一致 = 二次打包，**请勿安装**。
- ⚠️ `keystore/` 与 `signing.properties` **永不入库**（已在 `.gitignore` 里）；
  密钥丢失 = 再也无法发布升级包，务必离线备份两处。
- ⚠️ 发布后**不要更换签名密钥**（换 = 老用户无法覆盖升级）。

## 3. 时间戳存证（`attest/`）

用 git 提交历史 + **RFC-3161 可信时间戳**固定"某时刻我已拥有这份代码与这个包"：

| 文件 | 内容 |
|---|---|
| `attest/hashes.txt` | 版本、git commit、源码快照 SHA-256、APK SHA-256 |
| `attest/*.tsq` | 提交给时间戳服务的请求（DER） |
| `attest/*.tsr` | 时间戳服务返回的**签名时间戳**（freetsa.org，可离线验证） |
| `attest/DeskBand-src-*.zip` | 该版本的源码快照（`git archive` 产出） |

验证时间戳（需要 openssl）：

```bash
openssl ts -verify -in attest/DeskBand-2.1.28-APK.tsr \
  -queryfile attest/DeskBand-2.1.28-APK.tsq \
  -CAfile <(curl -s https://freetsa.org/files/cacert.pem) \
  -untrusted <(curl -s https://freetsa.org/files/tsa.crt)
```

> 更强的凭证（建议尽早办）：**计算机软件著作权登记**（中国版权保护中心，¥300 左右，
> 1~3 个月下证）与**工信部 App 备案** —— 这两样是官方机构出具、平台投诉下架也靠它们。

## 4. 授权范围

见 [LICENSE](LICENSE)：**保留所有权利**（未经书面许可不得复制、再分发、二次打包、
商用或声称自行开发）。

⚠️ **第三方素材**：App 中出现的角色立绘（如《孤独摇滚》相关角色）版权归其原作者，
**不在本工程授权范围内**，公开分发前请替换为原创素材。

## 5. 开发记录

`git log` 即完整开发时间线（每次提交都带中文说明），配合 `ENGINEERING.md`
（工程决策与踩坑记录）可以复原整个开发过程 —— 这也是权属的旁证。
