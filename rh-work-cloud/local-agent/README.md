# RH Work 本地监控端

用于一台长期在线的 Windows 公司电脑。主原则：ChatGPT 登录、额度读取、Work 消息发送都在这台真实电脑的 Edge/Chrome 内完成；Railway 只负责远程控制、定时、日志和任务队列。

## 首次使用

1. 解压 `RH-Work-Local-Monitor.zip` 到一个不会移动的目录，例如 `D:\RH-Work-Monitor`。
2. 双击 `start.cmd`。
3. 第一次会要求输入 RH Work Cloud 地址，直接回车使用默认地址即可。
4. 输入 RH Work Cloud 控制台密码完成设备配对。密码只用于首次换取设备令牌，之后本地保存的是设备令牌。
5. 程序会自动启动一个独立的 Edge/Chrome Profile，并打开 `https://chatgpt.com/`。
6. 在浏览器里手动登录 ChatGPT。账号密码、验证码不会交给 RH Work 程序。
7. 登录后程序会自动进入常驻模式，每分钟读取一次真实的“5 小时限额 / 每周限额”。
8. 双击 `install-autostart.cmd` 设置 Windows 登录后自动启动。
9. Windows 电源设置中把“睡眠”调整为“从不”；显示器可以关闭，不影响运行。

## 浏览器身份

本地监控端使用 `data/browser-profile` 作为独立浏览器数据目录。不要删除该目录，否则 ChatGPT 登录状态会丢失。

远程调试端口只绑定 `127.0.0.1:9222`，不会对局域网/公网开放。

## 工作方式

- 本地端每 20 秒向 Railway 发送心跳。
- 每 60 秒读取真实 ChatGPT 用量页面。
- Railway 网页点“读取真实额度”时，会命令这台电脑立即读取。
- Railway 网页点“立即继续 Work”时，任务会发送到这台电脑，由真实浏览器打开 Work URL、输入续跑文案并确认提交。
- 如果 5 小时或每周限额为 0%，服务器进入等待状态；恢复后本地端上报新额度，自动续跑可以再次提交。
- 本地电脑离线时，网页会显示设备离线；不会伪造额度。

## 日志

本地日志：`data/agent.log`

配置与设备令牌：`data/config.json`
