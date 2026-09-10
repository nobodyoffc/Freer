# task

[x] when creating team, the field of DOCK should be the sid of the DOCK (or BASE if no DOCK) of the mainFid instead of the url
[x] when user create a new team, should it generate the symkey soon instead of waiting confirmation and the promoting user? 
[x] combine the prikey importing pages
[] when sending a TX, add advanced button to allow user rearrange the inputs.

[x] 首身份自动生成
[x] 首币自动拆分3个
[x] DISK/DOCK自动注册

[] 需要做一个协议、代码、服务、应用管理版本系统
[] 发送交易设置dock后不应该再显示设置dock提示；
[x] 注册cid后，设置dock失败，确认后才恢复。
[] set master 'no enough CD.'
[x] search in usedCids
[] token
[] publish codes.
[] update services
[x] add disk mirror mirror.
[] create APIP protocol
[x] make up nerve server
[x] bitcore to freer, safe, freer/safe mac.
[x] upload server programs
[x] publish APPs
    [x] freer
    [x] safe
    [x] MyCoins
[x] publish NIDs
[x] update download.xml
[x] add uploadAll to FAPI client.
[x] add download file to protocol in the app
[x] DISK server on http. Free only for downloading. 
[x]link protocol, app to DISK. 

[x] modify protocols

[x] update the bundle prefix of the algorithm.
[x] cd for updating square
[x] about
[x] set home with sid.

[x] room: ROOM_ACCEPT, ROOM_DISBAND。解散时由房主发出 DISBAND 消息。成员端收到后标注已解散，可自主选择是否删除历史信息。
[x] show members
[x] p2p dock logic
[x] when 'im manager not ready, try later', return causes crash.
[x] pending message logic: reject and block it. accept and resume.
[x] the symkey sharing message is shown in p2p chat.

[x] opreturn list +
[x] fetch the messages of myself
[x] double notification ImManager not ready
[x] voice message
[x] no cid of chunhua
[x] when send is clicked, the waiting circle should pop up immediately. in carve, send, 
[x] choose the fast default Fapi services
[x] ROOT: search by height in block page
[x] test gid -> squareId
[x] test localName from list -> map
[x] show dock expire in the conversation
[x] channel label missed
[x] p2p chat setup:
    1. only contact. add contact before send p2p message to me.
    2. stranger dialog: add to blacklist
    3. ignore stranger
    4. accept stranger if it is in contacts.
[x] manage black list
[x] new peer dialog looks not good.
[x] apiGroups to components
[x] rate freer and others.
[x] server: every day task: delete expired dock items
[x] server: maxDataSize
[x] server: Dock items do not store in files
[x] when failed store dock, restart fudp and fapi? No
[x] failed state in red. 
[x] Chinese version of the chat type in the notification text above the input box

[x] no symkey dialog + cancel button
[x] don't show request fch dialog if the fapiClient isn't connected.

[x] only set dock. others are only for advanced.

[x] share-history
[x] group->square
[x] recharging should add orderVia the dealer of the app.
[x] max file size in DISK service
[x] input box
[x] hide input box and all buttons except back in left room/group/team
[x] leave room
[x] new room dialog
[x] when member leaves room, it should inform the owner. the owner should update the room. 
[x] why there are no delivered and read./group,team,room can not show such states.

[x] send file
[x] emoji
[x] control: hat, records, symkey
[x] receive from fudpNode

[x] delete local data; 
[x] upload: check first, then put. 200
[x] DISK operate list: check list; upload; download.
[] dialog:whisper,P2P
[] talk: 在线直发，不在线的dock
[] 对话不需要中介，中介用于群聊和组聊，组聊的解密怎么解决？去中介，发送者声明房间。
[] 链下List名单,按FID排列哈希为ID。创建和获得名单保存在聊天历史中。名单在db中持久化保存，可管理。
[] 代码整理：db已完成

[] cash
[] 文件加解密
[x] 备份私钥格式压缩
[x] export key page makeQR
[x] first loading camera
[x] 名单！
[x] 清除所有数据
[x] 返回需要验证密码
[x] 返回时输入不同密码的串台问题
[x] 文件存入download
[x] 文件备份导入
[x] qr扫描成功提示
[x] 验证交易，去掉支出总额
[x] 导入旧密签交易
[x] import crypt sign tx
[x] copy tx
[x] 测试多签，交易。解决数据丢失问题。

[x] hawk
[x] 密码
[x] 方形图标,圆形图标
[x] 改密码迁移多签库
[x] 无标签保存

[x] 生成随机数：结果 抖动
[x] \u003d -> =
[x] md5,sha1
[x] base32 random
[x] import totp
[x] qr copy to return

[x] import secret backup:json, key, header, json list
[x] import key backup

[x] multiSign: 

[x]multisignManager + localDB<P2SH>
[x] first page
* list ids, 
* Create FID(新建地址）, 
* Create TX（新建交易）,
* Sign TX（签署交易）,
* Build TX（完成交易）
[] keyTools: new random, priKey converter, pubKey converter, address converter
[] TOTP

[x] list in batch
[x] activities of 'Create'


[x] create EncryptActivity
[x] test DecryptActivity
[x] debug chooseKeyInfoFragment
[x] colors
[x] themes
[x] Label inside icons. 单词全填入，超过8字符以.代替。多词驼峰，总长超过8采用缩写