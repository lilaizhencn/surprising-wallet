# Cardano node UTxORPC

Unmodified `v1beta` protobuf definitions from IntersectMBO/cardano-api,
`cardano-rpc-11.2.0.0`, commit
[`70cb17639e8e704bcf06969ca2a42ed45ca5d680`](https://github.com/IntersectMBO/cardano-api/tree/70cb17639e8e704bcf06969ca2a42ed45ca5d680/cardano-rpc/proto/utxorpc).
Licensed under Apache-2.0; see LICENSE.

This is the built-in RPC bundled with cardano-node 11.1.3. Generate the Java
messages and gRPC stubs with the existing Maven protobuf plugin. Do not edit
generated sources or substitute the incompatible v1alpha protocol.

Use ReadGenesis, ReadParams, SearchUtxos, ReadUtxos, SubmitTx, ReadTip,
FetchBlock and FollowTip. This node does not implement transaction lookup,
mempool lookup, history dumping or server reflection. FetchBlock requires a hash; the native 11.1.3 node accepts hash-only and
returns the actual slot and height. Height alone is not a block reference. FollowTip resumes from
an explicit intersection and supplies original block bytes and parsed facts.
