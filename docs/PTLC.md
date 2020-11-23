# Point Time Locked Contracts (PTLCs)



**THIS FEATURE IS EXPERIMENTAL, DO NOT USE IT ON MAINNET WITH REAL FUNDS.**



This is the first iteration of PTLC implementation described here: https://suredbits.com/payment-points-part-1/.
The implementation requires some changes in the Lightning protocol, however we tried to make as few changes as possible. 
Because of that this version is partially trusting.



We introduced two new messages (`update_add_ptlc` and `commitment_signed_ptlc`), and reused `update_fulfill_htlc`
with a slightly different semantics.



Also, we introduced outputs for PTLC transactions, a feature bit that enables PTLC, and a new tagged field for invoices.

### Changes in the peer to peer protocol



At each hop the payment point transmitted in the `update_add_ptlc` got tweaked (tweaks are transmitted in onion packets)

The payment preimage set in `update_fulfill_htlc` got tweaked as well.

At each hop each node verifies that the payment preimage is in fact the corresponding private key for the hop's payment point.
And the payment initiator verifies that the payment preimage is actually a payment scalar from which the payment point from 
the invoice was derived. 

Since we use adaptor signatures (https://github.com/discreetlogcontracts/dlcspecs/pull/114) for signing PTLC outputs and the size of adaptor signatures is more than 64 bytes, 
we decided to introduce another new message `commitment_signed_ptlc`, which can hold both regular and adaptor signatures. 



```
    +-------+                                    +-------+
    |       |--(1)------ update_add_ptlc ------->|       |
    |       |                                    |       |
    |       |--(2)--- commitment_signed_ptlc --->|       |
    |   A   |<-(3)------ revoke_and_ack ---------|   B   |
    |       |                                    |       |
    |       |<-(4)--- commitment_signed_ptlc ----|       |
    |       |--(5)------ revoke_and_ack -------->|       |
    |       |                                    |       |
    |       |<-(6)---- update_fulfill_htlc ------|       |
    +-------+                                    +-------+
```



#### Adding an PTLC: update_add_ptlc



The main difference between `update_add_ptlc` and `update_add_htlc` is
that instead of `payment_hash` field it has `payment_point`. Also it has a special TLV type for the point tweaks.



1. type: 138 (`update_add_ptlc`)
2. data:
   * [`channel_id`:`channel_id`]
   * [`u64`:`id`]
   * [`u64`:`amount_msat`]
   * [`33*byte`:`payment_point`]
   * [`u32`:`cltv_expiry`]
   * [`1366*byte`:`tlv_onion_routing_packet`]
   

#### Removing an PTLC: `update_fulfill_htlc`, `update_fail_htlc`, and `update_fail_malformed_htlc`

`update_fulfill_htlc` has the same semantics as in the case HTLC, except that 
at each hop the preimage gets tweaked with the hop's point tweak. So at each hop the value of preimage is different. 

The semantics of `update_fail_htlc` and `update_fail_malformed_htlc` was not changes, the only difference if that `id` field represents PTLC id.     

#### Committing Updates: `commitment_signed_ptlc`



This message was introduced to accommodate adaptor signatures along with regular ECDSA signatures.



1. type: 139 (`commitment_signed_ptlc`)
2. data:
   * [`channel_id`:`channel_id`]
   * [`signature`:`signature`]
   * [`u16`:`num_htlcs`]
   * [`num_htlcs*signature`:`ptlc_signature`]
   

### Changes in the transaction and script format



#### Offered and Received PTLC Outputs



We use the same format for both offered and received PTLC outputs:



```
OP_DUP  OP_HASH160 <RIPEMD160(SHA256(revocationpubkey))> OP_EQUAL
OP_IF 
    OP_CHECKSIG
OP_ELSE 
    OP_2 <remotePtlcPubkey> <localPtlcPubkey> OP_2 OP_CHECKMULTISIG
OP_ENDIF
```


### PTLC feature flag



PTLC feature flag enables support of PTLC for a node. Also, it should be set in invoices to initiate PTLC payments. 
Note, that `var_onion_optin` feature should be set as well to support PTLC.



| Bits  | Name                             | Description                 | Context  | Dependencies      |
|-------|----------------------------------|-----------------------------|----------|-------------------|
| 58/59 | `ptlc`                           | Requires or supports PTLC   | IN9      | `var_onion_optin` |



### PTLC Tagged Field (Invoices)



The PTLC field gets populated if the node enables PTLC via setting PTLC feature flag.
Since `payment_hash` is a required field on invoices, it is set to the SHA256 of the payment point.

* (31): data_length 33. Payment point. Preimage of this provides proof of payment.



### Onion TLV type

Each hop should have its own point tweak, so it's encoded in the onion packet.

1. type: 0x5555555555555 (next point tweak)
2. data:
    * [32*byte : next_point_tweak]



#How to Run ad Test PTLC



## Setting up a regtest network of Eclair nodes



Download and install Bitcoin Core from https://bitcoin.org/en/download.



Open Bitcoin Core config file in your favorite text editor (the file is located at `~/.bitcoin/bitcoin.conf` on Linux, or `~/Library/Application Support/Bitcoin` on Mac OS X) 
and paste the configuration below.


```
regtest=1
server=1
txindex=1

rpcuser=foo
rpcpassword=bar

zmqpubrawblock=tcp://127.0.0.1:29000
zmqpubrawtx=tcp://127.0.0.1:29001
```



Start bitcoind



```bash
$ cd <Bitcoin core installation directory>/bin
$ ./bitcoind
```



In another terminal window generate some regtest coins.



```bash
$ cd <Bitcoin core installation directory>/bin
$ ./bitcoin-cli getnewaddress
# it will print a new address here, copy and paste it to the next commands
$ ./bitcoin-cli generatetoaddress 100 <paste_the_address_here>
$ ./bitcoin-cli generatetoaddress 10 <paste_the_address_here>
```



To build and run Eclair first you need to install Java: https://adoptopenjdk.net/?variant=openjdk11&jvmVariant=hotspot



Also, you'll need Maven to build Eclair, follow installation instructions here: https://maven.apache.org/install.html. 



Open another terminal and clone Eclair repository



```bash
$ cd
$ git clone https://github.com/bitcoin-s/eclair.git
```



Build Eclair package 



```bash
$ cd eclair
$ mvn install -DskipTests=true
```



Initialize test nodes using the launch script:



```bash
$ cd contrib
$ ./launch-network.rb init
```



This command will create 3 Eclair data directories `nodes/A` (Alice), `nodes/B` (Bob), and `nodes/C` (Carol) with config files. 
The most important config line is 

```
eclair.features.ptlc=optional
```



which enables PTLC. 

 

Also, the launch script creates a utility CLI scrips for each node: `eclair-cli-a`, `eclair-cli-b`, and `eclair-cli-c` 



Start the network:



```bash
$ ./launch-network.rb start
```



Open a channel from Bob to Carol.



```bash
$ ./eclair-cli-c getinfo
{
  "alias": "C",
  "nodeId": "C node Id",
  "publicAddresses": [
    "127.0.0.1:9837"
  ]
}
```



Find `nodeId` field copy and paste its value to the next command:



```bash
$ ./eclair-cli-b connect --uri=<paste_C_node_id_here>@127.0.0.1:9837
connected
$ ./eclair-cli-b open --nodeId=<paste_C_node_id_here> --fundingSatoshis=10000000
created channel <C node id>
```



In the `bitcoin-cli` terminal generate a block



```bash
$ ./bitcoin-cli generatetoaddress 1 <paste_the_address_here>
```



In Eclair terminal check if the channel is in `NORMAL` state



```bash
$ ./eclair-cli-c channels
[
  {
    "nodeId": "C node id",
    "channelId": "B -> C channel id",
    "state": "NORMAL",
  }
]
```

 

Similarly, open a channel from Alice to Bob.



```bash
$ ./eclair-cli-b getinfo
{
  "alias": "B",
  "nodeId": "B node Id",
  "publicAddresses": [
    "127.0.0.1:9836"
  ]
}
```



Find `nodeId` field copy and paste its value to the next command:



```bash
$ ./eclair-cli-a connect --uri=<paste_B_node_id_here>@127.0.0.1:9836
connected
$ ./eclair-cli-a open --nodeId=<paste_B_node_id_here> --fundingSatoshis=10000000
created channel <B node id>
```



In the `bitcoin-cli` terminal generate a block



```bash
$ ./bitcoin-cli generatetoaddress 1 <paste_the_address_here>
```



In Eclair terminal check if the channel is in `NORMAL` state



```bash
$ ./eclair-cli-a channels
[
  {
    "nodeId": "B node id",
    "channelId": "A -> B channel id",
    "state": "NORMAL",
  }
]
```



Generate 6 more blocks to make the nodes to propagate the channel announcements throughout the network.  



```bash
$ ./bitcoin-cli generatetoaddress 6 <paste_the_address_here>
```



### Sending PTLC payments



First Carol should generate an invoice.



```bash
$ ./eclair-cli-c createinvoice --description=test --amountMsat=1000000
{
  "prefix": "lnbcrt",
  "nodeId": "C node Id",
  "description": "test",
  "serialized": "lnbcrt...",
  "paymentHash": "fe5fe27cd48ee9a2f0262862f0978171e70df44bd15fabfa15562ac19111f1b8",
  "paymentPoint": "02972a5d8234b777cca1fbf9f8208584cb551fa1a81406839c1f528894ee903a4f",
  "expiry": 3600,
  "minFinalCltvExpiry": 30,
  "amount": 1000000,
  "features": {
    "activated": [
      {
        "name": "var_onion_optin",
        "support": "optional"
      },
      {
        "name": "ptlc",
        "support": "optional"
      }
    ],
    "unknown": []
  }
}
```



Elcair generates a payment scalar (an analog of payment preimage) and derives a payment point from it.
As you can see, the payment point is included into the invoice. The payment hash is just a hash of the payment point here.

Then Alice can pay the invoice.

Copy the serialized version of the invoice (the value of `serialized` field) and paste it to the next command



```bash
$ ./eclair-cli-a payinvoice --invoice=<the_serialized_invoice>
<payment id>
```

Check payment status:



```bash
$ ./eclair-cli-a getsentinfo --id=<paste_the_payment_id_here>
[
  {
    "id": "payment id",
    "status": {
      "type": "sent",
      ...
    }
  }
] 
```

You can see how payment de-correlation works from Bob's point of view



```
$ grep Diagnostic nodes/B/eclair.log 
2020-11-20 17:02:44,985 INFO  f.a.eclair.Diagnostics n:03406a5449e7d72f9c86f92d7f8576ae1e38b933f538a475086fbcdf02ff450240 c:b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7 - IN msg=UpdateAddPtlc(b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7,0,1001100 msat,03ce505511e352c3bc3e56bed538a5b31b7bcaf6e9e7068c195b7903726380ef01,CltvExpiry(293),OnionRoutingPacket(0,ByteVector(33 bytes, 0x036d3b7fa4a87a8374102769b89d7a6912c61530600a2066ced96f68b761a0cf5b),ByteVector(1300 bytes, #1235841525),2345a29171627546f84279d91e335fd075601a600ec629c879768c1104dcf22c))
2020-11-20 17:02:44,997 INFO  f.a.eclair.Diagnostics n:03406a5449e7d72f9c86f92d7f8576ae1e38b933f538a475086fbcdf02ff450240 c:b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7 - IN msg=CommitSigPtlc(b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7,0961602fe677f5046ce810b93d6fe54c4abf6d534b2c554361eda6ad6cd6bef460f928ef9725a6e680cb9ac4279dc03691fe2b9a159e8d27a24a6a5df2c7061c,List())
2020-11-20 17:02:45,012 INFO  f.a.eclair.Diagnostics n:03406a5449e7d72f9c86f92d7f8576ae1e38b933f538a475086fbcdf02ff450240 c:b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7 - OUT msg=RevokeAndAck(b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7,PrivateKey(91086e0f309d7aaf0e249e5936565dc7a0db12592bb6f582dce33dc0d04bda7e),03d65e20fea7325011df95cbb027ddf9df01f63c09860bda3bd38ebd264bedb842)
2020-11-20 17:02:45,022 INFO  f.a.eclair.Diagnostics n:03406a5449e7d72f9c86f92d7f8576ae1e38b933f538a475086fbcdf02ff450240 c:b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7 - OUT msg=CommitSigPtlc(b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7,7916f7c590bff642b39fa1ee4173badacb94aef8a83748820fb015070ab338d760d243abfb833735cf0f06f4616db06493f8b59e209745527612a55dca58746f,List())
2020-11-20 17:02:45,036 INFO  f.a.eclair.Diagnostics n:03406a5449e7d72f9c86f92d7f8576ae1e38b933f538a475086fbcdf02ff450240 c:b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7 - IN msg=RevokeAndAck(b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7,PrivateKey(67d338f2882e002582aceebe99e906d335db507450703c38e3e0500306010229),0384dac0457e08628220e4280f5650ca3f2efb68261d299a2e3ad97f11982d087e)
2020-11-20 17:02:45,067 INFO  f.a.eclair.Diagnostics n:02018bd2b8a14d33ad4d879f72ff804790474c7203f94ff5a967cb20688e78e10e c:12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42 - OUT msg=UpdateAddPtlc(12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42,0,1000000 msat,0278b892b78b06a49d9221392974288ef0365c4bf65b60bfc9f4deb3c979e8c484,CltvExpiry(149),OnionRoutingPacket(0,ByteVector(33 bytes, 0x021d7e0bcbc972d3714443262cc9c2eb576a868d2996c516a19bd4103c0354d99b),ByteVector(1300 bytes, #1999613457),885a2fc0dc4fe671a5e077b686ea4ec36a3f402d688660ea22fac84c43f9a121))
2020-11-20 17:02:45,071 INFO  f.a.eclair.Diagnostics n:02018bd2b8a14d33ad4d879f72ff804790474c7203f94ff5a967cb20688e78e10e c:12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42 - OUT msg=CommitSigPtlc(12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42,8579bb32f57181c35fd7eb0fb12ae23941d04b8dfbae3ba515caacd01548e33619f903d97670e2a09ade48f6d459023ddc18f05db05fee70c44c484ce2cafc51,List())
2020-11-20 17:02:45,087 INFO  f.a.eclair.Diagnostics n:02018bd2b8a14d33ad4d879f72ff804790474c7203f94ff5a967cb20688e78e10e c:12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42 - IN msg=RevokeAndAck(12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42,PrivateKey(69ec0ae29554d12b6b87ac1423a70cc3556caf1084744cb670da90a4abb4bdda),023a513214f34272ba7a0a38052ba886c6fe6a47ecb0487bcac6813d19f38e8a2b)
2020-11-20 17:02:45,097 INFO  f.a.eclair.Diagnostics n:02018bd2b8a14d33ad4d879f72ff804790474c7203f94ff5a967cb20688e78e10e c:12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42 - IN msg=CommitSigPtlc(12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42,e0d54985f448074e029f8fe88420dc4de787f230c2f0f76857e6470d873f874e5ca78f8a8e81844d42b93ff4b7d6bbe31e5e5718be43f6e46694520f82bc1afc,List())
2020-11-20 17:02:45,103 INFO  f.a.eclair.Diagnostics n:02018bd2b8a14d33ad4d879f72ff804790474c7203f94ff5a967cb20688e78e10e c:12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42 - OUT msg=RevokeAndAck(12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42,PrivateKey(7b79fac5ebd85dd7960621e3e593a076f79108cd7cd7817312a01edb353e7035),03b65d7afd7280ededb716122406eff02e3f3369280e8ef33eb4c47fd5b296c651)
2020-11-20 17:02:45,150 INFO  f.a.eclair.Diagnostics PAY n:02018bd2b8a14d33ad4d879f72ff804790474c7203f94ff5a967cb20688e78e10e c:12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42 - IN msg=UpdateFulfillHtlc(12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42,0,739e72332ea48d50262c28521bf509c0316b0253407a73ce11855d79f278ebad)
2020-11-20 17:02:45,157 INFO  f.a.eclair.Diagnostics n:02018bd2b8a14d33ad4d879f72ff804790474c7203f94ff5a967cb20688e78e10e c:12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42 - IN msg=CommitSigPtlc(12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42,a8d495cd22dfa41289179176a51b83ad469d4bbef2a0df29b40f20a02fd92a5c0919df06d13752f6c8b46a564c22245b44dfea6467818a938397cf8699140d8d,List())
2020-11-20 17:02:45,159 INFO  f.a.eclair.Diagnostics PAY n:03406a5449e7d72f9c86f92d7f8576ae1e38b933f538a475086fbcdf02ff450240 c:b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7 - OUT msg=UpdateFulfillHtlc(b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7,0,185e69265352a74c6cdb77811bd985e851fb7f64f7ba3e9569448ae8a4dca048)
2020-11-20 17:02:45,166 INFO  f.a.eclair.Diagnostics n:03406a5449e7d72f9c86f92d7f8576ae1e38b933f538a475086fbcdf02ff450240 c:b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7 - OUT msg=CommitSigPtlc(b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7,d1508279e3613deaaa5bddf116a5089e499e3263201385be0002b8d7aa53314d614a3e091a9d64ad9455e185fceaa26eaf75333f8ec51dbf6d1192f67af1a681,List())
2020-11-20 17:02:45,167 INFO  f.a.eclair.Diagnostics n:02018bd2b8a14d33ad4d879f72ff804790474c7203f94ff5a967cb20688e78e10e c:12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42 - OUT msg=RevokeAndAck(12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42,PrivateKey(8d687f09d76d2b7c7eb76e54a487d524cf320af9371c05917c8db2e0c39e2913),03d64c8150f5a78c10b044e112a2fd697b54116eacc75771516eadbf3a8200acaa)
2020-11-20 17:02:45,174 INFO  f.a.eclair.Diagnostics n:02018bd2b8a14d33ad4d879f72ff804790474c7203f94ff5a967cb20688e78e10e c:12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42 - OUT msg=CommitSigPtlc(12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42,c97adfbbe60c6c1a87a2925fd980c708d206bcafbd995bb2d186c6e41667b2eb037c1a56273646cbf2cfe000378a7d25b90be2e3fae34a1b80c51887d55c9ca0,List())
2020-11-20 17:02:45,180 INFO  f.a.eclair.Diagnostics n:03406a5449e7d72f9c86f92d7f8576ae1e38b933f538a475086fbcdf02ff450240 c:b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7 - IN msg=RevokeAndAck(b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7,PrivateKey(53a54e32769bc3a7daf0b689cde61ab79eb0e92a09f4b0950c5a2530c07fc69d),031b36bf1e7c64f6ae4de412a7eaed5cfa0155e87a4eab49085073a72859b6181e)
2020-11-20 17:02:45,188 INFO  f.a.eclair.Diagnostics n:03406a5449e7d72f9c86f92d7f8576ae1e38b933f538a475086fbcdf02ff450240 c:b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7 - IN msg=CommitSigPtlc(b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7,9d276624a5c0a63c8f5e758e24a1a6d6aed144056033fa89c34d9bc968d26b6058d019558954ec8ad438a7540a047e27a4399f92b43d88b5e855a1e810bc32d2,List())
2020-11-20 17:02:45,189 INFO  f.a.eclair.Diagnostics n:02018bd2b8a14d33ad4d879f72ff804790474c7203f94ff5a967cb20688e78e10e c:12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42 - IN msg=RevokeAndAck(12cd3a993ac45e1b5d8898632d78fad1e6b6d070564d265067a2cf57b6741d42,PrivateKey(bfe2993f9290229e7446e1d30a8275590c6135abf7d91612018c2a765a4966af),034d85995ac130a1d63d783010b415ef8dc7a64900ed49292d3af470aae89cc3d9)
2020-11-20 17:02:45,194 INFO  f.a.eclair.Diagnostics n:03406a5449e7d72f9c86f92d7f8576ae1e38b933f538a475086fbcdf02ff450240 c:b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7 - OUT msg=RevokeAndAck(b7a71f769b6dad3b268d7bfd8b8bca5de496f3d897a2015cbc619a41455187c7,PrivateKey(004d8f46d44fb224d77ffd1010312a57f65709aded6dd53d8dad006589bc5a1c),02c5d8667d84af876592d1bf43a0b968241382ea98e05ded9089d85f2953911d8b)
```



As you can see it receives an `UpdateAddPtlc` with one payment point from Alice and then forward it to Carol with a tweaked one.
Also, it receives an `UpdateFulfillHtlc` with one payment preimage from Carol and then tweaks it and forwards it or Alice. 



To stop the network run



```bash

$ ./launch-network.rb stop
```

## Known Limitations

1. The current version doesn't allow using HTLC's if PTLC mode is enabled.
2. This implementation is trustfull, it provides signatures of the commitment transaction before receiving all PTLC signatures.
