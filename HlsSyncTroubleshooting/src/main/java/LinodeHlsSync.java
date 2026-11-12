/*
 * 客户交付：Linode HLS 文件同步工具（Java 17 或更高版本）。
 * 本文件包含完整实现，不依赖本项目其他 Java 类，也不调用 Python。
 *
 * 第三方依赖（含传递依赖，请使用 Maven/Gradle 下载）：
 *   software.amazon.awssdk:s3:2.30.38
 *   software.amazon.awssdk:url-connection-client:2.30.38
 *
 * 编译：javac -encoding UTF-8 -cp "lib/*" LinodeHlsSync.java
 * 运行：java -cp ".:lib/*" LinodeHlsSync 播放列表URL 唯一对象前缀
 * Windows 的运行时 classpath 分隔符使用分号 ; 替代冒号 :。
 *
 * 必填环境变量：LINODE_ACCESS_KEY、LINODE_SECRET_KEY、LINODE_ENDPOINT、
 * LINODE_REGION、LINODE_BUCKET。访问密钥不得写入代码或提交到版本库。
 * 当前测试环境的非敏感配置：
 *   LINODE_ENDPOINT=https://region-id.linodeobjects.com
 *   LINODE_REGION=region-id
 *   LINODE_BUCKET=example-bucket
 *
 * 可选环境变量：
 *   LINODE_PUBLIC_BASE_URL：返回URL的域名及可选路径；默认使用桶的虚拟主机域名。
 *   SOURCE_REFERER / SOURCE_USER_AGENT：源站要求的HTTP请求头。
 *   HLS_MAX_FILES：最大资源数量，默认10000。
 *   HLS_MAX_BYTES：最大下载总字节数，默认10737418240（10 GiB）。
 *
 * 可选参数：--referer URL、--user-agent 值、--allow-host 域名（可重复）、
 * --work-dir 新目录。源站需要Referer时，请通过参数或环境变量显式配置。
 * User-Agent默认使用Mozilla/5.0。
 *
 * 工作流程：递归下载完整静态HLS资源，改写播放列表引用，上传所有文件，逐个下载
 * 回读并核对SHA-256及Content-Type，全部成功后才向标准输出打印JSON结果。
 * JSON中的playlistUrl是入口播放列表，objects列出所有Linode对象URL。
 * 进度写入标准错误输出；失败时退出码为1，不打印成功结果。
 * 源站临时网络错误和429/5xx最多尝试3次，并遵守不超过60秒的Retry-After。
 *
 * 每次使用新前缀，避免覆盖现有对象。失败时保留工作目录；桶内可能残留部分
 * 已上传文件，程序不自动删除。成功后工作目录中也会保存result.json。
 *
 * 不关闭HTTPS证书校验，不把目标桶密钥发送给源站。仅允许初始源站域名，额外
 * CDN或重定向域名需通过--allow-host显式允许。HTTP仅用于本机回环测试。
 * 支持普通静态VOD、嵌套播放列表、媒体分片、初始化文件及identity AES-128
 * 密钥文件的复制；不解密、不转码。暂不支持直播、LL-HLS、变量替换、内容引导、
 * DRM许可证获取、分段上传、断点续传。工作目录需有足够磁盘空间。
 *
 * 返回的是未签名URL；本程序不修改ACL、桶策略或CORS。需要公开播放时，运营方
 * 应配置对象读取权限及播放器来源的CORS。仅设置桶ACL为公共READ，不代表对象
 * 必然允许匿名GetObject。私有对象需采用认证网关或覆盖全部依赖请求的授权方案。
 */
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

/** 静态HLS URL -> 完整下载 -> 校验上传 -> 返回Linode URL。 */
public final class LinodeHlsSync {
    private static final Pattern ATTRIBUTE = Pattern.compile("(?<start>(?:^|[:,])URI=\")(?<uri>[^\"]+)(?<end>\")");
    private static final Set<String> PLAYLIST_TAGS = Set.of("#EXT-X-MEDIA:", "#EXT-X-I-FRAME-STREAM-INF:");
    private static final List<String> UNSUPPORTED = List.of("#EXT-X-DEFINE:", "#EXT-X-PART:", "#EXT-X-PRELOAD-HINT:", "#EXT-X-RENDITION-REPORT:", "#EXT-X-CONTENT-STEERING:");
    private final Map<URI, Asset> assets = new LinkedHashMap<>();
    private final Set<String> hosts = new HashSet<>();
    private final Path directory;
    private final URI root;
    private final String referer, userAgent;
    private final int maxFiles;
    private final long maxBytes;
    private long downloaded;
    private record Asset(String name, boolean playlist) {}
    private record Fetched(Path file, URI finalUri) {}
    private static final class TransferFailure extends IOException {
        private static final long serialVersionUID = 1L;
        TransferFailure(String message) { super(message); }
    }
    private Fetched fetch(URI uri) throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            Path file = Files.createTempFile(directory, "download-", ".part");
            HttpURLConnection connection = null;
            URI current = uri;
            try {
                for (int redirects = 0; redirects <= 5; redirects++) {
                    validate(current);
                    connection = (HttpURLConnection) current.toURL().openConnection(proxy(current));
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(15000); connection.setReadTimeout(60000);
                    connection.setRequestProperty("User-Agent", userAgent);
                    if (!referer.isBlank()) connection.setRequestProperty("Referer", referer);
                    int status = connection.getResponseCode();
                    if (Set.of(301,302,303,307,308).contains(status)) {
                        String location = connection.getHeaderField("Location"); connection.disconnect();
                        if (location == null || redirects == 5) throw new TransferFailure("源站重定向无效或次数过多");
                        current = clean(current.resolve(location)); continue;
                    }
                    if (status != 200) {
                        if (attempt < 3 && Set.of(429,500,502,503,504).contains(status)) {
                            String retry = connection.getHeaderField("Retry-After");
                            long delay = attempt * 1000L;
                            if (retry != null) {
                                try { delay = Math.max(delay, Long.parseLong(retry.trim()) * 1000L); }
                                catch (NumberFormatException ignored) {
                                    try { delay = Math.max(delay, java.time.Duration.between(java.time.Instant.now(), java.time.ZonedDateTime.parse(retry, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); }
                                    catch (java.time.format.DateTimeParseException invalid) { throw new TransferFailure("源站Retry-After格式无效，请稍后重试"); }
                                }
                            }
                            if (delay > 60000) throw new TransferFailure("源站要求较长限流等待，请稍后重新运行");
                            connection.disconnect(); Thread.sleep(delay);
                            throw new RetryableSourceFailure();
                        }
                        throw new TransferFailure("源站返回HTTP " + status);
                    }
                    try (InputStream input = connection.getInputStream(); OutputStream out = Files.newOutputStream(file)) {
                        byte[] buffer = new byte[256 * 1024]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            downloaded += count;
                            if (downloaded > maxBytes) throw new TransferFailure("源站下载总字节数超过限制");
                            out.write(buffer, 0, count);
                        }
                    }
                    return new Fetched(file, current);
                }
                throw new TransferFailure("源站重定向次数过多");
            } catch (RetryableSourceFailure retry) {
                Files.deleteIfExists(file);
            } catch (IOException failure) {
                Files.deleteIfExists(file);
                if (failure instanceof TransferFailure || failure instanceof javax.net.ssl.SSLException || attempt == 3) throw failure;
                System.err.println("源站读取暂时失败，重试 " + attempt + "/3");
                Thread.sleep(attempt * 1000L);
            } finally { if (connection != null) connection.disconnect(); }
        }
        throw new TransferFailure("源站下载重试次数已用尽");
    }
    private static final class RetryableSourceFailure extends IOException { private static final long serialVersionUID = 1L; }

    private LinodeHlsSync(URI root, Path directory, Set<String> extraHosts, String referer, String userAgent, int maxFiles, long maxBytes) {
        this.root = root; this.directory = directory; this.referer = referer; this.userAgent = userAgent;
        this.maxFiles = maxFiles; this.maxBytes = maxBytes;
        hosts.add(root.getHost()); hosts.addAll(extraHosts);
    }
    private static boolean starts(String line, Collection<String> tags) {
        return tags.stream().anyMatch(line::startsWith);
    }
    private void validate(URI uri) throws IOException {
        boolean local = "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost").contains(uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !local) || uri.getUserInfo() != null || !hosts.contains(uri.getHost()))
            throw new TransferFailure("源站依赖或重定向的域名/协议未获允许：" + uri.getHost());
    }
    private static URI clean(URI uri) {
        String value = uri.toASCIIString(); int fragment = value.indexOf('#');
        return URI.create(fragment < 0 ? value : value.substring(0, fragment)).normalize();
    }
    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static Proxy proxy(URI uri) {
        if (Set.of("127.0.0.1", "localhost").contains(uri.getHost())) return Proxy.NO_PROXY;
        String bypass = System.getenv().getOrDefault("NO_PROXY", System.getenv().getOrDefault("no_proxy", ""));
        for (String entry : bypass.split(",")) {
            String host = entry.trim();
            if (host.equals("*") || host.equals(uri.getHost()) || (!host.isEmpty() && host.startsWith(".") && uri.getHost().endsWith(host))) return Proxy.NO_PROXY;
        }
        String protocol = "https".equals(uri.getScheme()) ? "HTTPS_PROXY" : "HTTP_PROXY";
        String value = System.getenv().getOrDefault(protocol, System.getenv(protocol.toLowerCase(Locale.ROOT)));
        if (value == null || value.isBlank()) return Proxy.NO_PROXY;
        URI endpoint = URI.create(value);
        if (endpoint.getUserInfo() != null) throw new IllegalArgumentException("带认证的源站代理需要配置JVM认证器");
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(endpoint.getHost(), endpoint.getPort() < 0 ? 80 : endpoint.getPort()));
    }
    private String download(URI input, boolean playlistHint, int depth) throws Exception {
        URI uri = clean(input);
        Asset known = assets.get(uri);
        if (known != null) return known.name();
        validate(uri);
        if (depth > 64) throw new TransferFailure("播放列表嵌套超过64层");
        if (assets.size() >= maxFiles) throw new TransferFailure("源站资源数量超过限制");
        Fetched fetched = fetch(uri);
        URI current = fetched.finalUri();
        Path temporary = fetched.file();
        boolean playlist;
        try {
            byte[] magic;
            try (InputStream source = Files.newInputStream(temporary)) { magic = source.readNBytes(16); }
            String head = new String(magic, StandardCharsets.UTF_8);
            playlist = playlistHint || head.startsWith("#EXTM3U") || head.startsWith("\uFEFF#EXTM3U");
            String suffix = ".bin";
            Matcher extension = Pattern.compile("(\\.[a-zA-Z0-9]{1,8})$").matcher(uri.getPath());
            if (extension.find()) suffix = extension.group(1).toLowerCase(Locale.ROOT);
            String name = uri.equals(root) ? "index.m3u8" : digest(uri.toASCIIString()) + (playlist ? ".m3u8" : suffix);
            // 先登记资源，再递归依赖，避免重复下载和循环引用。
            assets.put(uri, new Asset(name, playlist));
            if (playlist) {
                if (Files.size(temporary) > 16 * 1024 * 1024) throw new TransferFailure("播放列表大小超过16 MiB");
                String text = Files.readString(temporary, StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                List<String> lines = text.lines().toList();
                if (lines.isEmpty() || !lines.get(0).equals("#EXTM3U")) throw new TransferFailure("HLS播放列表格式无效");
                if (lines.stream().anyMatch(line -> starts(line, UNSUPPORTED))) throw new TransferFailure("LL-HLS、变量替换或内容引导需要专门处理");
                boolean master = lines.stream().anyMatch(line -> line.startsWith("#EXT-X-STREAM-INF:") || starts(line, PLAYLIST_TAGS));
                if (!master && !lines.contains("#EXT-X-ENDLIST")) throw new TransferFailure("仅支持已结束的静态点播播放列表");
                List<String> rewritten = new ArrayList<>(); boolean nextPlaylist = false;
                for (String line : lines) {
                    if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                        if (!line.contains("METHOD=NONE") && (!line.contains("METHOD=AES-128,") || (line.contains("KEYFORMAT=") && !line.contains("KEYFORMAT=\"identity\""))))
                            throw new TransferFailure("仅支持identity AES-128密钥文件，不支持DRM迁移");
                    }
                    if (!line.isBlank() && !line.startsWith("#")) {
                        line = download(current.resolve(line.trim()), nextPlaylist, depth + 1); nextPlaylist = false;
                    } else {
                        Matcher attributes = ATTRIBUTE.matcher(line); StringBuffer result = new StringBuffer();
                        boolean hint = starts(line, PLAYLIST_TAGS);
                        while (attributes.find()) {
                            String local = download(current.resolve(attributes.group("uri")), hint, depth + 1);
                            attributes.appendReplacement(result, Matcher.quoteReplacement(attributes.group("start") + local + attributes.group("end")));
                        }
                        attributes.appendTail(result); line = result.toString();
                        if (line.startsWith("#EXT-X-STREAM-INF:")) nextPlaylist = true;
                    }
                    rewritten.add(line);
                }
                Files.writeString(temporary, String.join("\n", rewritten) + "\n", StandardCharsets.UTF_8);
            }
            Files.move(temporary, directory.resolve(name));
            System.err.println("已发现/下载资源数：" + assets.size() + " 个");
            return name;
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    public static String contentType(Path file) {
        String name = file.toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (name.endsWith(".ts")) return "video/mp2t";
        if (name.endsWith(".mp4") || name.endsWith(".m4s")) return "video/mp4";
        if (name.endsWith(".aac")) return "audio/aac";
        if (name.endsWith(".vtt")) return "text/vtt";
        return "application/octet-stream";
    }
    private static void validatePlaylists(Path root, List<Path> files) throws Exception {
        var attribute = java.util.regex.Pattern.compile("(?:^|[:,])URI=\"([^\"]+)\"");
        for (Path file : files) {
            if (!file.toString().toLowerCase(Locale.ROOT).endsWith(".m3u8")) continue;
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty() || !lines.get(0).equals("#EXTM3U")) throw new IllegalArgumentException("播放列表无效：" + root.relativize(file));
            for (String line : lines) {
                List<String> references = new ArrayList<>();
                if (!line.isBlank() && !line.startsWith("#")) references.add(line.trim());
                var matcher = attribute.matcher(line);
                while (matcher.find()) references.add(matcher.group(1));
                for (String reference : references) {
                    URI uri = URI.create(reference);
                    if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getPath() == null || uri.getPath().startsWith("/") || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPath().isBlank())
                        throw new IllegalArgumentException("播放列表仍含外部、绝对路径或签名引用，需先本地化：" + root.relativize(file));
                    Path dependency = file.getParent().resolve(uri.getPath()).normalize();
                    if (!dependency.startsWith(root) || !files.contains(dependency))
                        throw new IllegalArgumentException("播放列表依赖不存在或超出工作目录：" + root.relativize(file));
                }
            }
        }
    }

    public static void uploadDirectory(Path directory, String prefix, java.util.function.Consumer<String> progress) throws Exception {
        Path root = directory.toRealPath();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("指定路径必须为目录");
        if (!prefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) throw new IllegalArgumentException("请使用非空相对前缀，仅包含字母、数字、下划线、连字符及路径分隔符");
        URI endpoint = URI.create(required("LINODE_ENDPOINT"));
        if (!"https".equals(endpoint.getScheme()) && !("http".equals(endpoint.getScheme()) && Set.of("127.0.0.1", "localhost").contains(endpoint.getHost())))
            throw new IllegalArgumentException("目标端点必须使用HTTPS；HTTP仅允许本机回环测试");
        String bucket = required("LINODE_BUCKET");
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList();
        }
        if (files.isEmpty()) throw new IllegalArgumentException("没有待上传文件");
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("仅允许普通文件：" + root.relativize(file));
        }
        validatePlaylists(root, files);
        // 先上传媒体依赖，后上传播放列表；每次使用新的对象前缀。
        files = new ArrayList<>(files);
        files.sort(Comparator.comparing((Path p) -> p.toString().toLowerCase(Locale.ROOT).endsWith(".m3u8")).thenComparing(Path::toString));
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(endpoint).region(Region.of(required("LINODE_REGION")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(required("LINODE_ACCESS_KEY"), required("LINODE_SECRET_KEY"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            for (Path file : files) {
                String key = prefix + "/" + root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType(file)).build(), file);
                // 使用流式回读，避免把大型媒体文件全部载入内存。
                MessageDigest local = MessageDigest.getInstance("SHA-256"), remote = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(file)) { input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), local)); }
                try (var input = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
                    if (!contentType(file).equals(input.response().contentType())) throw new IllegalStateException("Content-Type校验不一致：" + key);
                    input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), remote));
                }
                if (!MessageDigest.isEqual(local.digest(), remote.digest())) throw new IllegalStateException("SHA-256校验不一致：" + key);
                progress.accept("上传并回读校验成功：" + key + " (" + contentType(file) + ")");
            }
        }
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少环境变量：" + key);
        return value;
    }
    private static String objectUrl(URI base, String key) throws Exception {
        String path = base.getPath().replaceAll("/$", "") + "/" + key;
        return new URI(base.getScheme(), null, base.getHost(), base.getPort(), path, null, null).toASCIIString();
    }
    private static void execute(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("用法：LinodeHlsSync 源URL 唯一对象前缀 [--referer URL] [--user-agent 值] [--allow-host 域名] [--work-dir 新目录]");
        URI source = clean(URI.create(args[0])); String prefix = args[1];
        if (!prefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) throw new IllegalArgumentException("对象前缀格式无效");
        String bucket = required("LINODE_BUCKET"); URI endpoint = URI.create(required("LINODE_ENDPOINT"));
        required("LINODE_REGION"); required("LINODE_ACCESS_KEY"); required("LINODE_SECRET_KEY");
        String referer = System.getenv().getOrDefault("SOURCE_REFERER", "");
        String agent = System.getenv().getOrDefault("SOURCE_USER_AGENT", "Mozilla/5.0");
        Set<String> hosts = new HashSet<>(); Path work = null;
        for (int i = 2; i < args.length; i += 2) {
            if (i + 1 == args.length) throw new IllegalArgumentException("参数缺少取值");
            switch (args[i]) {
                case "--referer" -> referer = args[i + 1];
                case "--user-agent" -> agent = args[i + 1];
                case "--allow-host" -> hosts.add(args[i + 1]);
                case "--work-dir" -> work = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("未知参数");
            }
        }
        URI base = URI.create(System.getenv().getOrDefault("LINODE_PUBLIC_BASE_URL", endpoint.getScheme() + "://" + bucket + "." + endpoint.getAuthority()));
        if (base.getHost() == null || base.getQuery() != null || base.getFragment() != null || base.getUserInfo() != null) throw new IllegalArgumentException("返回URL的基础地址配置无效");
        if (work == null) work = Files.createTempDirectory("linode-hls-");
        else { Path parent = work.toAbsolutePath().getParent(); Files.createDirectories(parent); Files.createDirectory(work); }
        try { Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rwx------")); } catch (UnsupportedOperationException ignored) {}
        Path files = Files.createDirectory(work.resolve("assets"));
        System.err.println("工作目录：" + work.toAbsolutePath());
        LinodeHlsSync sync = new LinodeHlsSync(source, files, hosts, referer, agent,
            Integer.parseInt(System.getenv().getOrDefault("HLS_MAX_FILES", "10000")), Long.parseLong(System.getenv().getOrDefault("HLS_MAX_BYTES", "10737418240")));
        sync.download(source, true, 0);
        // 所有源站资源下载并完成校验后，才开始写入目标桶。
        uploadDirectory(files, prefix, System.err::println);
        StringBuilder result = new StringBuilder("{\n  \"playlistUrl\": ").append(quote(objectUrl(base, prefix + "/index.m3u8")))
            .append(",\n  \"bucket\": ").append(quote(bucket)).append(",\n  \"prefix\": ").append(quote(prefix))
            .append(",\n  \"verifiedObjectCount\": ").append(sync.assets.size()).append(",\n  \"objects\": [\n");
        boolean first = true;
        for (Asset asset : sync.assets.values()) {
            if (!first) result.append(",\n"); first = false;
            String key = prefix + "/" + asset.name();
            result.append("    {\"key\": ").append(quote(key)).append(", \"url\": ").append(quote(objectUrl(base,key))).append("}");
        }
        result.append("\n  ]\n}\n");
        Files.writeString(work.resolve("result.json"), result, StandardCharsets.UTF_8);
        System.out.print(result);
    }
    public static void main(String[] args) {
        try { execute(args); }
        catch (Exception failure) {
            // SDK/HTTP异常详情可能含签名或源站令牌，因此不直接输出。
            if (failure instanceof TransferFailure) System.err.println("原因：" + failure.getMessage());
            System.err.println("同步失败（" + failure.getClass().getSimpleName() + "）。未返回成功URL；请检查源站访问、配置、资源限制及目标权限。目标前缀可能残留部分文件。");
            System.exit(1);
        }
    }
}
