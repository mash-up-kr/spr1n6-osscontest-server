[한국어](OPENSQL_HA_CLUSTER.md) | **English**

# OpenSQL HA cluster design and tests

This document covers the design of the DB HA cluster built on Tmax OpenSQL, the problems found while building it and how they were fixed, and how its features are tested. OpenSQL is a commercial product, so this setup cannot be brought up without a license. The results in this document were measured on a DB cluster the team ran separately, under a license provided by TmaxTibero. The actual cloud infrastructure is defined in [`terraform/`](../terraform/README.en.md).

## 1. Cluster layout

![OpenSQL HA cluster](images/opensql-ha-cluster.png)

A single OpenSQL handles document and permission storage, vector search, and the indexing job queue. Three DB nodes are tied together with Patroni and etcd, and the two OpenProxy instances in front of them share a single VIP.

- **Automatic failover**: Patroni uses a leader lock managed by etcd to decide which node is entitled to be Primary. When the Primary fails and the lock expires, another node that meets the promotion conditions takes the lock and is promoted to the new Primary. etcd keeps the leader lock consistent through majority consensus.
- **Recovering and rejoining a failed node**: Once the failed former Primary comes back, it uses `pg_rewind` to align its data history with the new Primary, rejoins as a Replica, and resumes WAL replication.
- **Connection failover and read load balancing**: OpenProxy queries Patroni's state to tell the current Primary from the Replicas and routes connections accordingly. Writes go to the Primary, searches go to the Replicas, and connection load is spread across the Replicas.

## 2. Watchdog-based fencing

This section explains how we found a split brain that the leader lock alone could not prevent, and how we handled it with Patroni's watchdog and system settings.

### Background and design

Even when Patroni fails to renew the leader lock because its process stopped or stalled, the existing Primary may not stop accepting writes right away. If the leader lock expires in that state and another Replica is promoted, **a split brain occurs: two Primaries accept writes at the same time and data consistency can break.**

To prevent this, we enabled Patroni's watchdog. While Patroni is healthy it keeps resetting the watchdog timer, and if Patroni stops and the resets stop, the kernel force-reboots that server. The watchdog timer is set to fire before the leader lock expires, so that **the existing Primary stops accepting writes before a new Primary is promoted**.

### Problems found and fixes

- **Found**: When only Patroni was force-killed, the watchdog reboot came about 2 seconds after the new Primary was promoted. During that window there were two Primaries, and 4 writes that had been acknowledged as successful were lost.
- **Cause**: Turning on the watchdog was not enough on its own. With the timer settings at the time, the reboot of the existing Primary could land later than the leader lock expiry.
- **Fix**: We shortened Patroni's check interval (`loop_wait`) from 10 to 5 seconds and raised the watchdog's safety margin (`safety_margin`) from 5 to 10 seconds. The leader lock lifetime (`ttl`) stayed at 30 seconds.
- **Re-verification**: Reproducing the same failure, the existing Primary rebooted about 7.8 seconds before the new Primary was promoted. There was 0 seconds of overlapping Primaries and 0 observed data loss.

### System resource stabilization

The watchdog is the last safety net for when Patroni stops. Separately, we tuned system settings to reduce the chance of Patroni stalling or dying from memory pressure or swapping in the first place.

- **Limit swapping**: Lowered `vm.swappiness` from 60 to 10 to cut unnecessary swapping under memory pressure.
- **Protect core processes**: Adjusted OOM scores so that Patroni and the main PostgreSQL process are not the first to be killed when memory runs out, and handled query processes separately.
- **Memory budget**: Tuned PostgreSQL settings to fit the limited instance memory and lower the chance of excessive memory use.

## 3. Replication strategy: asynchronous replication

This section explains why, after considering synchronous replication to put data consistency first, we chose asynchronous replication because of a compatibility problem between components.

### Background and design

When forced to choose between performance and data consistency, we chose consistency. For a document management service, reducing the chance of data loss matters more than slightly slower write responses, so we initially set out to use Patroni's synchronous replication mode.

### Problem found and decision

- **Found**: With synchronous replication mode on, connections to the DB dropped while we were verifying failover. OpenProxy could not find a Primary to send writes to.
- **Cause**: In synchronous replication mode the replica's role is reported as `sync_standby`, and the OpenProxy 1.1.3 we use could not handle that role.
- **Decision**: Since fixing OpenProxy ourselves was not practical, we adopted **asynchronous replication** to keep automatic failover and the DB connection path stable. The resulting risk of data loss on failure and Replica read lag are covered in [7. Limitations and next steps](#7-limitations-and-next-steps).

## 4. OpenProxy redundancy with a VIP

Even when all three DB nodes are healthy, the app cannot reach the DB if the OpenProxy in front of them stops. This section explains how we put two OpenProxy instances behind one address (VIP), and the limitations we hit and solved implementing it on AWS.

### Background and design

The app uses only the VIP as its DB address. OpenProxy runs on db-02 (Active) and db-03 (Standby), and OpenProxy's built-in VRRP decides which of the two holds the VIP. If the Active stops, the Standby takes over the VIP and connections continue on the same address.

### AWS limitations and solution

- **Limitation**: AWS decides which server receives a private IP solely by which network interface (ENI) the IP is registered on. Unlike a regular network, AWS ignores a new owner announcing "this IP is mine now", so OpenProxy's VRRP alone does not move the VIP. The VRRP traffic between the two OpenProxy instances (IP protocol 112) was also blocked by the security group.
- **Solution**: The hook OpenProxy runs when it becomes the VIP owner (`pre_promote_script`) calls the AWS API to move the VIP to its own ENI. The permission for this call comes from an IAM role that only allows moving the IP between the two ENIs, and the security group was opened for VRRP traffic. Both are in the Terraform code.

### Problems found and fixes

- **Found**: Reproducing the Active OpenProxy process freezing for 3.4 seconds or more and then resuming cut VIP access off completely. While it was frozen, db-03 took the VIP and AWS's VIP assignment moved to db-03 too. When db-02 resumed, it still considered itself the VIP owner, and db-03 stepped down and released the VIP. AWS kept sending traffic to db-03, so neither node received VIP traffic and it had to be recovered by hand.
- **Cause**: The only point where AWS's VIP assignment was changed was "when taking the VIP". db-02 had merely frozen and resumed without any state change, so the hook never ran again.
- **Fix**: Added a hook that runs when releasing the VIP (`pre_demote_script`), so the node stepping down hands the VIP over to the other node's ENI.
- **Re-verification**: In all four cases (pause then resume, stop, restart, force kill), VIP access recovered within 1.5 seconds. In feature test H-5 (stopping the Active OpenProxy), writes were interrupted for 1.5 seconds (threshold 5 seconds), the AWS VIP move took 2.6 seconds, and failback to the original node also happened automatically.

## 5. Pinning write connections to the Primary

This section explains how we found that OpenProxy was also sending write connections to Replicas, and how we fixed it through the app's DB connection settings.

### Problems found and fixes

- **Found**: Even on a pool whose default role was set to Primary, OpenProxy 1.1.3 did not apply that setting (`SHOW SERVER ROLE` returned `any`). Out of 30 connections through that pool, only 8 went to the Primary and the other 22 were spread across the two Replicas. Writes sent to a Replica fail.
- **Fix**: The app now sends `SET SERVER ROLE TO 'default'` first when it opens a connection, so the role configured on the pool takes effect. In a transaction pool this setting only lasts for one transaction, so we switched the pool to session mode, and we adjusted the JDBC options (`assumeMinServerVersion`, `preferQueryMode`) so that this statement is the first query and in a form OpenProxy can handle.
- **Re-verification**: With the role set, all 30 connections went to the Primary. The write pool connects only to the Primary and the search pool only to Replicas, and in feature test H-2 not a single search query reached the Primary.

### Cleaning up connections broken during a switchover

- **Found**: At the moment the Primary changes, connections left in the connection pool hit two errors: a write sent to the former Primary that has become a Replica (`25006`), and OpenProxy failing to find a server to connect to (`58000`). By default the connection pool (HikariCP) does not treat these two errors as connection problems, so it does not discard those connections.
- **Fix**: Connections that receive either error are now evicted from the connection pool immediately and replaced with new ones.
- **Re-verification**: In a test where the Primary was switched over on purpose while requests kept coming in, the app was interrupted for 5–6 seconds and recovered without a restart.

## 6. Feature tests and monitoring

### Test items

We defined six DB HA features and wrote a scenario to verify each one. Each item is tested with the [`ha-test/`](../ha-test/README.md) tool. The script runs the baseline check, fault injection, measurement, restoration, and Pass/Fail verdict in order, so anyone who runs it gets the same verdict.

| # | Item | What it checks |
|---|---|---|
| H-1 | DB data replication | A row written through the VIP replicates to both Replicas within 1 second, with streaming and Lag 0 |
| H-2 | Read request distribution | Search queries are handled only by Replicas, and uploads (writes) by the Primary |
| H-3 | Automatic DB failover | When the Primary is killed, a node is promoted without human intervention and writes resume within 45 seconds |
| H-4 | Data retention after failover | Every row acknowledged as successful before the failure remains on the new Primary |
| H-5 | Automatic DB connection path switch | When the Active OpenProxy is stopped, writes work again on the same VIP within 5 seconds |
| H-6 | Per-node status | Role, state, and Lag of all three nodes are shown, and stops and restorations are reflected |

### Using the tools

- **`ha-test`**: This tool injects real faults into the shared cluster, so first walk through the flow without servers using `--simulate`, and run it only when `check` reports OK. Commands and test order are in [ha-test/README.md](../ha-test/README.md).
- **Node monitoring**: A Grafana dashboard shows the role, state, and replication lag of the three DB nodes, plus OpenProxy, VIP, and etcd status. When a test runs, `ha-test` draws vertical lines on the dashboard at fault injection, promotion, and write resumption. How to start it and where the metrics come from are in [Node monitoring dashboard in the development guide](DEVELOPMENT.en.md#node-monitoring-dashboard), and screen setup for tests is in [the demo screen setup section of ha-test/README.md](../ha-test/README.md#시연-화면-준비-터미널--grafana).

## 7. Limitations and next steps

### Limitation 1: Data loss on failure

- **Problem**: With asynchronous replication, the Primary acknowledges a write without waiting for Replicas to finish replicating it. So when the Primary fails, data not yet replicated can be lost even though the write was acknowledged.
- **Current mitigation**: `maximum_lag_on_failover=1MB` keeps Replicas with large replication lag from being promoted, and normal replication lag is observed in Grafana.
- **Remaining limitation**: Limiting promotion candidates does not guarantee the loss stays under 1MB. Replication lag monitoring also cannot detect or prevent actual loss.
- **Alternative considered**: Having the application confirm, based on the log position (LSN), that Replicas have finished replicating before acknowledging a write. However, it would also require guaranteeing that a Replica holding that data is the one promoted on failover, which makes it complex to implement.
- **Decision**: We judged it excessive for the current service scale and requirements and did not adopt it. We will revisit it if data durability requirements become stricter.

### Limitation 2: Replica read lag

- **Problem**: A read made before a change on the Primary reaches the Replica can return stale data.
- **Current mitigation**: Reads where freshness matters, such as document lists and details, are served by the Primary, and **only search requests are distributed to Replicas**.
- **Alternatives considered**: Waiting after a document update until Replicas apply it, sending a check request to the Primary on every search, and confirming whether a Replica has applied the change based on the log position (LSN) at write time.
- **Decision**: Normal replication lag was on the order of a few milliseconds, and the benefit did not justify the extra requests and implementation complexity, so we did not adopt them.
- **Next step**: If search result freshness becomes a problem in real operation, we will consider the LSN-based check.
