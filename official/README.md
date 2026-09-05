# official/ — 官方 App 分析材料（不入库）

本目录内容**不提交到 Git**（版权与体积原因），仅本地保留。结构约定：

```
official/
├── com.cndatacom.campus.cdccportalgd.apk   # 官方 APK（广东校园 v4.0.2104）
└── decompiled/                             # jadx 反编译产物
    └── jadx/{sources,resources,...}
```

重新获取方式：
1. APK：从官方渠道或已备份的安装包获取，放入本目录；
2. 反编译：`jadx --show-bad-code -d decompiled/jadx com.cndatacom.campus.cdccportalgd.apk`
   （jadx ≥ 1.5；APK 为 360 加固，dex 仅壳代码，资源与 Manifest 可正常提取）。

分析结论见内部开发文档 `docs/02-官方App分析.md`（仅本地保留，不入库）。
