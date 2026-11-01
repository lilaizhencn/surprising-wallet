import { readFileSync } from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { signingDomainFromMetadata } from '../src/signing-domain.mjs';
const sample=JSON.parse(readFileSync(new URL('./fixtures/polkadot-hub-v252-metadata.json',import.meta.url)));
const types=new Map();for(const entry of sample.extensionTypeDefs){types.set(entry.type,entry.typeDef);types.set(entry.implicit,entry.implicitDef);}
const domain=metadata=>signingDomainFromMetadata(metadata,id=>types.get(id));
const copy=()=>structuredClone(sample.extrinsicMetadata);
test('actual mainnet V16 metadata selects V4 pipeline 0 and excludes V1 signature/PGAS fields',()=>{
  const result=domain(copy());assert.equal(result.extensionVersion,0);assert.equal(result.extrinsicVersion,4);assert.equal(result.extensions.length,13);
  assert(result.extensions.includes('PrevalidateAttests'));assert(!result.extensions.includes('VerifyMultiSignature'));assert(!result.extensions.includes('AsPgas'));
  assert.deepEqual(result.extraFields,{era:'ExtrinsicEra',nonce:'Compact<Index>',tip:'Compact<Balance>',assetId:'TAssetConversion',mode:'u8'});
});
test('absent or duplicated version-0 pipeline is rejected',()=>{
  const missing=copy();delete missing.transactionExtensionsByVersion['0'];assert.throws(()=>domain(missing));
  const duplicate=copy();duplicate.transactionExtensionsByVersion['0'].push(0);assert.throws(()=>domain(duplicate));
});
test('V1 pipelines cannot be smuggled into V4 as empty extensions',()=>{
  const wrong=copy();wrong.transactionExtensionsByVersion['0']=wrong.transactionExtensionsByVersion['1'];assert.throws(()=>domain(wrong),/unreviewed SCALE layout/);
});
test('changed nonce layout or nonempty claimed-empty extensions are rejected',()=>{
  const changed=copy();changed.transactionExtensions.find(x=>x.identifier==='CheckNonce').type=14;assert.throws(()=>domain(changed),/CheckNonce/);
  const nonempty=copy();nonempty.transactionExtensions.find(x=>x.identifier==='PrevalidateAttests').type=14;assert.throws(()=>domain(nonempty),/PrevalidateAttests/);
});
test('V5-only metadata does not authorize a signed V4 wire',()=>{
  const wrong=copy();wrong.versions='0x05';assert.throws(()=>domain(wrong),/does not advertise signed V4/);
});
