# Neutrino Wallet

## Building

Checkout the `develop` branch to build Neutrino enabled Eclair. Use the regular Maven build procedure:

```shell
mvn package
``` 

to build the zip archive (see [the build docs](../BUILD.md) for more info). 

## Configuring

Create `~/.eclair` directory.

Create the config file `~/.eclair/eclair.conf` 

```
eclair.chain = testnet
eclair.api.enabled = true
eclair.api.password = bar
eclair.api.port = 8080
eclair.mindepth-blocks = 1
eclair.watcher-type = neutrino
```

and `~/.eclair/bitcoin-s.conf`

```
bitcoin-s.logging.level = info
bitcoin-s.logging.logback = true
bitcoin-s.node.peers = ["neutrino.testnet3.suredbits.com"]
bitcoin-s.wallet.requiredConfirmations = 1
```
Note, that Eclair always overrides the value `bitcoin-s.network` with the value of `eclair.chain`. 

## Running

Unzip file `eclair-node/target/eclair-node-<version>-<commit>-bin.zip` that you built before.

To run Neutrino enabled Eclair native secp256k1 library should be disabled.
 
Run it using this command:

```shell script
DISABLE_SECP256K1=1 eclair-node-<version>-<commit_id>/bin/eclair-node.sh
```

