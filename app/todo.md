春花秋月何时了

[] 取消signIn
[] sessionManager

[] Post -> sha256 sign -> AES
[x] CidInfo -> Freer.java
[] 春花的向导

[x] rawTxInfo.multisign->senderKeyInfo.multisig
[x] refused move v1 from API path to body
[x] rate history
[x] TX
[x] Paste in DISK
[x] isNew: income, expense, tx, news, rate

1. app
2. service
3. protocal
4. 

1. hash 160: redeemScript in opreturn
2. pares redeemScript to p2shMap, id is hash160, check them
3. pay to list -> icon
4. lock time mode -> icon
5. lockTime apply to Pay to list and multiple contact
6. pay to send to list. Json icon [,]

[x] if API1 is down, how to switch to API2

[x] multisig: 1 normal to self normal 
[x] multisig: 1 normal to self cltv
[x] multisig: 1 cltv to self normal
[x] multisig: 1 cltv + 1 normal to 1 cltv + 1 normal + 1 change
  fee sufficient

[x] when multisig send tx, the change is not added to the cashManager.

[x] send to a normal cltv tx
[x] liveFid.multisig

[] Multisign to multisig

[] reorg cltv
[] send multisig+cltv
[] cashType: p2sh-cltv, p2sh-multisig, p2sh-multisig-cltv, p2pkh


[] rate at FID
[] fchParser: cltv->cash or p2sh
[] fee

[] new fid buy API: cidByIds, cashSearch, broadcast
[] CLTV send to future

[] Disk
[] Build:feipProtocol, code, service, app,news

[] request API by custom sorts, service
[] talkUnit: each room has a idList
[x] send cltv+normal
[x] secret list unlimited

Safe
[x] decrypt icon failed to load cipher
[x] create mail: fid,pubkey. waiting dialog
[x] create contact: fid,pubkey input
[x] HatManager
[x] FcObjectManager:
[x] safe: cash fcEntityFragment
[x] freer: multisig Tx , Sign multisig tx
[x] backup prikey QR + hex +checkbox

[x] after send TX: waiting dialog; send button disable
[x] check password twice when change password and from sleep
[x] update contact: show the fid avatar
[x] load cash activity: add waiting dialog
[x] pay activity: put into a scroll view
[x] pay to API: mark paid
[x] pay: no cdd when confirming TX

[x] mail search chinese words
[x] forbid Landscape
[x] height to fcDate
[x] auto secret
[x] initiating Error when load QR code activity
[x] safe: secret edit
[x] safe: on chain = null
[x] cash import and export.
[x] current password backed up. onchain is null
[x] connect with Safe secret
[x] item secret layout
[x] waiting dialog when chosen CID
[x] secret deleted remind again
[x] export secret
[x] keys
[x] safe; secret backup modes
[x] merge item_card
[x] new cash marked with red point
[x] new mail, new cash marked with red point
[x] my notice fee.
[x] add Guide to Contact
[x] nobody
[x] create contact in @chooseContactActivity
[x] layout issues of carveActivity and createTxActivity on Samsung phone.
[x] @SendTxActivity: put the value of Carve in a container with outline.
[x] choose page: disable the avatar of the toolbar
[x] create password: toasted existed! but actually created.
[x] check top up
[x] check set freer

[x] big bug: resume from other password can get into the former page of other password.
[x] find nice key: save to DB
[x] 2 times scan
[x] initiating camara
[x] input prikey and label: no configure

[x] backup prikey
[x] change API services

[x] settings 的存储没有使用passwordname，这会导致不同密码相同fid的setting冲突。
[x] Deleted card add ic_delete
[x] switch to multisig, servant, watched + waitingDialog
[x] All check box is partly covered @add servants

* task
  * mail
  * disk
  * build(feipProtocol,code,service,app)
  * news(statement,build news)
  * token
  * read
  

[x] add decrypted field
[x] failed to decrypted logic

[x] detail contains json. make it nice. add a json icon
[x] multisig send tx
[x] 考虑是否重构localDB.no
[x] 测试HawkDB. no
[x] DB 增加 lastHeight.no
[x] request page count 会导致请求不完。如果按照lastHeight，下次请求就会跳过剩余项目。
[x] 几种不同的数据加载情况，应该拆分成不同函数。

[x] secret
[x] test off-line broadcast, sign tx
[x] off chain icon being clicked lead to carving

[x] cidManager
[x] toastManager

[x] click the FID, liveFid should be copied instead of the mainFid.
[x] switching id have to switch the prikey, cardManager...
[x] detail of multisig
[x] toolbar+avatar+freer/fid
[x] cash
[x] set freer in setting and liveFidCard(name)

[x] switch watching FIDs
[x] switch servants
[x] test TxHandler
[x] test FeipHandler
[x] test SetMasterActivity
[x] test List<Cash> TxCreator.getIssuedCashListForFid(String signedTx,String fid)