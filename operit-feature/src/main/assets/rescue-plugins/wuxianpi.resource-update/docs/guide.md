# APK 配套更新

`wuxianpi.resource-update 4.0.1` 只处理 APK 更新后 Android 私有目录中的 service-manager 连接。它不是 WuxianPi、Termux 或 OpenHouse 运行资源更新器。

当前 APK 的通过条件只有：

```text
serviceManagerBaseUrl 是带显式端口的 loopback HTTP URL
hasToken=true
Android 私有连接能够再次读取
```

连接已经存在时，工作流只读取并确认，不访问 Termux。连接缺失时才执行 `wuxianpi-setup connection-info`，随后调用宿主的 `write_service_manager_connection` 原子写入 Android 私有存储。Token 只作为受控工具参数传递，不得出现在对话、日志或记忆中。

本插件不会：

```text
读取或比较 openhouse-core-stack
投递 APK TAR
下载市场资源归档
更新 service-manager
运行 wuxianpi-setup activate
更新或检查 WuxianPi、Runtime、Web、wuyou、Ubuntu
检查 20765
```

`reason=first-install` 仍由首次安装插件负责，不能使用本插件降低首次安装门禁。`reason=apk-update` 达到连接标准后才能标记 `satisfied`。失败时保留提醒；用户可返回桌面选择“结束本次提醒”，该操作只写 `dismissed`，不会伪造成功或修改 Termux。

未来 APK 如果使用新的 service-manager Android 接口，应由当时的插件版本先做功能探针。能力已经存在时不更新；能力缺失时只更新 service-manager，再重复探针。普通 Termux 内部更新和完整资源集合 sequence 不参与判断。
