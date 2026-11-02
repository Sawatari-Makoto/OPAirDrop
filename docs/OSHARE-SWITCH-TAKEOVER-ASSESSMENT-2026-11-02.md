# 开关接管设想评估与修改意见

日期：2026-11-02。背景：v1.1.6 已刷入设备并完成 iPhone 实机互传验证（识别 + 传输均正常）。  
本文评估"用 OShare 总开关接管 AirDrop 互传启停"这一设想，覆盖用户提出的四个子问题，  
并给出可落地的修改意见。

## 一、设想回顾

用户设想：复用 OShare 自身的互传开关（`oshare_state`）作为 AirDrop 互传的总闸，  
用"**一个开关"替代/简化当前 `supervisor + ui-lifecycle` 的门控机制；关闭后给一段**  
可选等待时间再释放会话。

## 二、关键事实（本次实机 / 静态确证）

| 编号 | 事实                                                                                          | 来源                      |
| -- | ------------------------------------------------------------------------------------------- | ----------------------- |
| F1 | `oshare_state` 存于 `Settings.System`，root 可直接 `settings get system oshare_state` 读；实测当前值 = 1 | 实机 `settings get`       |
| F2 | `oshare_state` 由 `com.oplus.oshare.utils.p1.u` 写入（混淆类名）                                     | 静态反编译                   |
| F3 | root 可直接 `settings put system oshare_state <值>` 写入，**无需 hook 设置 UI**                        | root settings 权限        |
| F4 | AirDrop 广播在 `wifiStaInfo=null` 下仍注册成功，即不依赖 OShare 的 Wi-Fi 状态门控                              | 实机日志                    |
| F5 | `oshare_airdrop_device_types.xml` 已有 24 条 `airdrop:<id> → PHONE` 映射                         | 资源文件                    |
| F6 | `IOSShareActivity` 与 `ShareActivity` 均已被 `ui-lifecycle.sh foreground_matches()` 覆盖          | 源码 `ui-lifecycle.sh:55` |
| F7 | 保温窗口 `IDLE_DEFAULT_TIMEOUT=120`；`idle-sleep.sh set-timeout <秒>` 已支持任意秒数（上限 86400）           | 源码 `idle-sleep.sh`      |

## 三、四个子问题结论

### 1. 单开关可行性 —— 可行，但应走「root 轮询 Settings」，而非 hook

**结论：单开关做"总闸"可行；但实现上推荐 root 直接读写 `Settings.System`，不引入 LSPosed hook。**

- `oshare_state` 语义是 OShare 的"互传总开关"（允许/禁止互传发现与接收）。root 读它零成本  
  （F1/F3），`settings put` 写它同样零成本。
- 若改为 hook `com.oplus.oshare.utils.p1.u` 来读/写，会引入混淆类名的跨版本脆弱性（F2），  
  收益为零——root 已经能直接读写同一个值。
- **边界（重要）**：单开关只能表达"允许/禁止"二态，表达不了当前生命周期  
  "分享面板打开 → 武装会话 → 关闭 → 保温 N 秒 → 释放"。因此：
  - 单开关适合做**第二层总闸**（覆盖 `disabled` 之外的用户级开关）；
  - 但**不能**替代 `ui-lifecycle` 的发送时点门控与 `idle-sleep` 的保温释放——它们管的是  
    "什么时候武装/释放"这个时间维度，开关管的是"允不允许"这个资格维度，二者正交。

### 2. hook `com.coloros.oshare` 建议 —— 读写开关不需要 hook；发送桥接保留

- **读/写 `oshare_state`：不 hook**，用 root `settings`（F1/F3）。
- 现有 OShare + Mosey 的 LSPosed 作用域（AirDrop 发送桥接、Chooser 接入、设备类型映射）  
  与开关无关，**保持不变**。
- 若要感知"用户在设置页点开关"做即时联动，root 侧 `settings` 轮询或 `content observer`  
  即可，仍不建议 hook `p1.u`。
- 结论：**不新增 `com.coloros.oshare` 的设置 UI hook**，这是本次设想里最该砍掉的一环。

### 3. 与 iPhone 互传入口改用 airdrop —— 已就绪，无需改动

- `IOSShareActivity` / `ShareActivity` 均已覆盖（F6），24 条 `airdrop:<id> → PHONE`  
  映射已就绪（F5），本次实机已验证识别 + 传输正常。
- 唯一可选的产品层优化：Chooser 中 iPhone 目标的 secondary name / 角标展示，属锦上添花，  
  非本次必须。

### 4. 1/2/3/5/10 分钟可选关闭等待 —— 底层已就绪，只缺 WebUI 下拉

- 底层 `idle-sleep.sh set-timeout <秒>` 已支持任意秒数（上限 86400），默认 120（F7）。
- 现状：`settings get global idle_timeout` 为 null → 走默认 120 秒；WebUI 有"常驻"开关  
  （`airdrop-keepalive`），但**保温时长没有下拉选项**。
- 建议：WebUI「功耗」区加保温时长单选，选项 60 / 120 / 180 / 300 / 600 秒  
  （1 / 2 / 3 / 5 / 10 分钟），落到 `settings put global idle_timeout <秒>`。  
  0 秒或"常驻"复用已有 `airdrop-keepalive` 开关语义，避免重复。

## 四、修改意见汇总（按优先级）

| 优先级 | 事项                                                        | 改动范围                                                | 风险 |
| --- | --------------------------------------------------------- | --------------------------------------------------- | -- |
| P1  | WebUI 保温窗口下拉：1/2/3/5/10 分钟                                | 纯前端（`App.vue` + `api.ts` 加 `saveIdleTimeout`），底层已支持 | 极低 |
| P2  | 可选：加"总开关"绑定 `oshare_state`（root 轮询）作为 `disabled` 之外的第二层总闸 | `settings.sh` 增 `oshare-state` 子命令 + supervisor 判定  | 低  |
| —   | iPhone 入口 airdrop 化                                       | 已完成并实机验证，无需改                                        | —  |
| —   | hook `com.coloros.oshare` 设置 UI                           | **不建议**，用 root settings 替代                          | —  |

## 五、结论

开关接管设想**成立但需收敛**：单开关做"总闸"可行，实现走 root `settings` 读写  
`oshare_state`（F1/F3），**不 hook 设置 UI**；生命周期门控（ui-lifecycle + idle-sleep）  
与开关是正交关系，保留不合并；"1/2/3/5/10 分钟可选等待"底层已就绪，只需补 WebUI 下拉  
（P1）。iPhone 入口 airdrop 化与 24 条设备映射均已就绪并实机验证通过。
