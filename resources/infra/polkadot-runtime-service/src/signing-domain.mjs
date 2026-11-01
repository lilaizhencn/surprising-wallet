import { expandExtensionTypes } from '@polkadot/types/extrinsic/signedExtensions';

const EMPTY = new Set(['AuthorizeCall','CheckNonZeroSender','CheckWeight','WeightReclaim','StorageWeightReclaim','EthSetOrigin','PrevalidateAttests']);

/** Metadata V16 lists a union of pipelines. Legacy signed V4 uses pipeline 0 only. */
export function signingDomain(api) {
  return signingDomainFromMetadata(api.runtimeMetadata.asLatest.extrinsic.toJSON(), id => api.registry.lookup.getTypeDef(id).type);
}

export function signingDomainFromMetadata(metadata, typeOf) {
  if (typeof metadata.versions !== 'string' || !/^0x(?:[0-9a-f]{2})+$/.test(metadata.versions) || !Buffer.from(metadata.versions.slice(2),'hex').includes(4)) throw new Error('runtime does not advertise signed V4');
  const definitions=metadata.transactionExtensions, indices=metadata.transactionExtensionsByVersion?.['0'];
  if (!Array.isArray(definitions) || !Array.isArray(indices) || !indices.length || indices.length>20 || new Set(indices).size!==indices.length) throw new Error('runtime V4 extension pipeline 0 is absent or ambiguous');
  const selected=indices.map(index => {
    if (!Number.isSafeInteger(index) || index<0 || index>=definitions.length) throw new Error('runtime extension index is invalid');
    return definitions[index];
  });
  const names=selected.map(value=>value.identifier);
  if(new Set(names).size!==names.length) throw new Error('runtime extension names are duplicated');
  for(const entry of selected) {
    const extra=typeOf(entry.type), implicit=typeOf(entry.implicit);
    const fail=()=>{throw new Error(`unreviewed SCALE layout for ${entry.identifier}`);};
    if(EMPTY.has(entry.identifier)) {if(extra!=='Null'||implicit!=='Null')fail();continue;}
    switch(entry.identifier) {
      case 'CheckSpecVersion': case 'CheckTxVersion': if(extra!=='Null'||implicit!=='u32')fail();break;
      case 'CheckGenesis': if(extra!=='Null'||implicit!=='H256')fail();break;
      case 'CheckMortality': if(extra!=='Era'||implicit!=='H256')fail();break;
      case 'CheckNonce': if(extra!=='Compact<u32>'||implicit!=='Null')fail();break;
      case 'ChargeTransactionPayment':
        if(!['Compact<u128>','{"tip":"Compact<u128>"}'].includes(extra)||implicit!=='Null')fail();break;
      case 'ChargeAssetTxPayment': {
        let fields;try{fields=JSON.parse(extra);}catch{fail();}
        // Fee asset is always None; the Some payload is never encoded by this gateway's caller.
        if(Object.keys(fields).join(',')!=='tip,assetId'||fields.tip!=='Compact<u128>'||!/^Option<.+>$/.test(fields.assetId)||implicit!=='Null')fail();break;
      }
      case 'CheckMetadataHash':
        if(extra!=='{"mode":"FrameMetadataHashExtensionMode"}'||implicit!=='Option<[u8;32]>')fail();break;
      default: fail();
    }
  }
  return {extrinsicVersion:4,extensionVersion:0,extensions:names,
    extraFields:expandExtensionTypes(names,'extrinsic'),additionalSignedFields:expandExtensionTypes(names,'payload')};
}
