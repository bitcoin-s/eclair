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
See [Eclair configuration docs](https://github.com/ACINQ/eclair#configuring-eclair) for more detailed instructions.

Also you'll need `~/.eclair/bitcoin-s.conf`

```
bitcoin-s.logging.level = info
bitcoin-s.logging.logback = true
bitcoin-s.node.peers = ["neutrino.testnet3.suredbits.com"]
bitcoin-s.wallet.requiredConfirmations = 1
```
See [bitcoin-s configuration docs](https://bitcoin-s.org/docs/config/configuration#example-configuration-file) for more info.

Note, that Eclair always overrides the value of `bitcoin-s.network` with the value of `eclair.chain`, and the value of `bitcoin-s.datadir` with `eclair.datadir`. 

## Running

Unzip file `eclair-node/target/eclair-node-<version>-<commit>-bin.zip` that you built before.

To run Neutrino enabled Eclair native secp256k1 library should be disabled.
 
Run it using this command:

```shell script
DISABLE_SECP256K1=1 eclair-node-<version>-<commit_id>/bin/eclair-node.sh
```

To fund the wallet generate a new address using CLI and send some sats to it: 
```shell script
eclair-cli getnewaddress
```
Currently, there's no way to send the funds out from the wallet other than using Lightning.

