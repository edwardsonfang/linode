# HLS 资源同步至 Linode Object Storage

本目录提供静态 HLS 资源同步工具。输入入口 `.m3u8` URL，递归下载主/子播放列表、媒体分片、初始化文件和支持的密钥文件，改写引用后上传到 Linode，并逐个 GET 回读核对 SHA-256 与 Content-Type。只有全部成功才返回入口和全部对象的 Linode URL。

## 中文单文件交付

- 源码：`src/main/java/LinodeHlsSync.java`，不依赖其他项目类或 Python。
- 使用说明：`客户使用说明.md`。
- 最小 Maven 依赖配置：`客户依赖配置.xml`。

需要 JDK 17+ 和 AWS SDK for Java v2。按使用说明获取依赖，也可在本目录构建：

```sh
mvn package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
bash sync-url-java.sh 'https://media.example.invalid/video/master.m3u8' 'hls-example-001'
```

以上域名仅为占位示例，运行前请替换。通过环境安全注入 `LINODE_ACCESS_KEY`、`LINODE_SECRET_KEY`，并设置 `LINODE_ENDPOINT`、`LINODE_REGION`、`LINODE_BUCKET`。源站要求额外请求头时，显式传入 `--referer`、`--user-agent`；额外 CDN 域名使用 `--allow-host`。

结果 JSON 写入标准输出，进度写入标准错误。请使用新前缀，避免覆盖已有对象。失败时不会返回成功 URL，但可能保留部分上传对象及本地工作文件。

## 本地验证

Python 仅用于测试和可选的辅助下载流程，不是 Java 客户交付的运行依赖。

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
mvn package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
.venv/bin/python -m unittest discover -s tests -p 'test_*.py' -v
.venv/bin/python tests/integration.py
```

`test_download.py` 验证 Python 辅助下载器；`test_java_remote.py` 通过真实 Java HTTP/S3 SDK 请求验证递归同步、去重、URL 输出及失败行为。`integration.py` 验证本地目录上传及源站下载到对象回读的完整流程。测试使用本机 HTTP/S3 形状的服务，不连接真实客户环境，不验证 Linode 权限或 SigV4 服务端实现。

`fixtures/hls/` 是自行生成的两秒纯色视频，只有合成测试内容，不含客户媒体。历史实站测试验证了完整对象回读和通过认证网关播放，但不代表匿名播放、桶策略或 CORS 已正确配置。

可选 Python 流程：`download_hls.py` 下载并改写资源，`run.sh` 上传本地目录，`sync_from_url.sh` 串联两步。包含源站 URL 的 manifest 必须留在受保护的本地目录，不能提交或作为媒体资源上传。

## 范围与访问权限

支持普通静态 VOD、URI 行、常见带引号的 `URI=` 属性和 identity AES-128 密钥文件复制。拒绝直播、LL-HLS、变量替换、内容引导和非 identity DRM；不是完整 HLS 规范校验器。不提供转码、DRM 许可获取、多段上传或断点续传。

返回 URL 不带签名。本工具不修改 ACL、桶策略或 CORS。匿名播放要求播放列表及其全部依赖均可读取；跨域播放器还要求相应 CORS。私有内容应使用认证网关或完整的依赖授权方案，不能只给主播放列表签名。

## 公开发布边界

本目录的示例使用保留占位域名和合成数据。客户原始附件、真实源站及桶标识、视频、截图、签名 URL、凭据、实站日志、构建产物和交付 ZIP 不纳入公开提交。原仓库其他目录的历史内容不属于此次改动范围。
