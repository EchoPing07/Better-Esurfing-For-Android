# cipher 包来源说明

## 旧代 9 算法

涉及 `cipher.go` / `keydata.go` / `aescbc.go` / `aesecb.go` / `desedecbc.go` /
`desedeecb.go` / `sm4cbc.go` / `sm4ecb.go` / `modxtea.go` / `modxteaxteaiv.go` / `zuc.go` / `helpers.go`。

全部代码移植自 [xxmod/EsurfingGo](https://github.com/xxmod/EsurfingGo) 的 `cipher` 包
（MIT License, Copyright (c) 2026 xxmod），包含 9 种手机通道加密算法与固定密钥表、单元测试向量。

本项目（Better-Esurfing-For-Android）按 MIT 协议使用并致谢。
原始算法逆向成果源自 Rsplwe/ESurfingDialer 及官方客户端。

## 新代 9 算法 + PC 通道 6 算法

涉及 `cipher_new.go` / `tea_variant.go` / `snow3g_variant.go` / `cipher_new_test.go` 与 `testdata/kat/`。

移植自作者本人的姊妹项目
[Esurfing-go-webui](https://github.com/DreamwareN/Esurfing-go-webui)（Apache License 2.0），
结构与参数化构造器对齐其 `cipher_new.go` / `cipher_tea.go` / `cipher_snow3g.go`。

上游参考实现（见该项目 README 的「算法来源与验证」一节）：

- 新代 9 + PC 通道 6 算法：[BadGhost520/ESurfingClient-CVersion](https://github.com/BadGhost520/ESurfingClient-CVersion)
  （Apache License 2.0；[MYHealer/esp32_esurfing](https://github.com/MYHealer/esp32_esurfing) 同源交叉验证）
- [Ironjhin/EsurfingClient_Android](https://github.com/Ironjhin/EsurfingClient_Android)：上述 C 引擎的下游 Android 移植版（未附 LICENSE），
  新代变体的转录对照副本

其中第三代真变体（变体 TEA×2 / SNOW3G）为照 C 参考源逐语句转录的 Go 实现，非工程代码复制；
算法源头仓库为 Apache-2.0，其下游移植 Ironjhin/EsurfingClient_Android 未附 LICENSE，按惯例在此明确注明算法行为参考来源。

## 密钥说明

全部算法（共 24 个 Algo-ID）的密钥与 IV 均为**协议固定常量**（逆向自官方客户端内置映射表），
并非用户密钥；加密仅用于满足服务端协议校验，不具备防窃听 / 防篡改的安全强度。
