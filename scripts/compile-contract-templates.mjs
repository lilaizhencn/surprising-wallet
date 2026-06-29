import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const compilerRoot = path.join(root, "tools", "contract-compiler");
const require = createRequire(path.join(compilerRoot, "package.json"));
const evmSolc = require("solc");
const tronSolc = require("solc-0-8-19");
const contractsRoot = path.join(
  root,
  "backendservices",
  "wallet-parent",
  "wallet-server",
  "src",
  "main",
  "resources",
  "contracts"
);

const targets = [
  {
    family: "evm",
    compiler: evmSolc,
    contracts: ["TokDouERC20.sol", "TokDouERC721.sol"],
    settings: {},
  },
  {
    family: "tron",
    compiler: tronSolc,
    contracts: ["TokDouTRC20.sol", "TokDouTRC721.sol"],
    settings: { evmVersion: "istanbul" },
  },
];

function findImport(importPath) {
  const candidates = [
    path.join(root, "node_modules", importPath),
    path.join(compilerRoot, "node_modules", importPath),
  ];
  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      return { contents: fs.readFileSync(candidate, "utf8") };
    }
  }
  return { error: `Import not found: ${importPath}` };
}

for (const target of targets) {
  compileTarget(target);
}

function compileTarget(target) {
  const resourceDir = path.join(contractsRoot, target.family);
  const artifactDir = path.join(resourceDir, "artifacts");
  const sources = Object.fromEntries(
    target.contracts.map((file) => [file, { content: fs.readFileSync(path.join(resourceDir, file), "utf8") }]),
  );
  const input = {
    language: "Solidity",
    sources,
    settings: {
      optimizer: { enabled: true, runs: 200 },
      ...target.settings,
      outputSelection: {
        "*": {
          "*": ["abi", "evm.bytecode.object", "evm.deployedBytecode.object"],
        },
      },
    },
  };
  const output = JSON.parse(target.compiler.compile(JSON.stringify(input), { import: findImport }));
  const errors = output.errors ?? [];
  const fatal = errors.filter((error) => error.severity === "error");
  if (fatal.length > 0) {
    throw new Error(fatal.map((error) => error.formattedMessage).join("\n"));
  }
  fs.mkdirSync(artifactDir, { recursive: true });
  for (const file of target.contracts) {
    const contractName = path.basename(file, ".sol");
    const compiled = output.contracts[file][contractName];
    const artifact = {
      contractName,
      sourceName: file,
      compilerVersion: target.compiler.version(),
      abi: compiled.abi,
      bytecode: compiled.evm.bytecode.object,
      deployedBytecode: compiled.evm.deployedBytecode.object,
    };
    if (target.settings.evmVersion) {
      artifact.evmVersion = target.settings.evmVersion;
    }
    fs.writeFileSync(path.join(artifactDir, `${contractName}.json`), `${JSON.stringify(artifact, null, 2)}\n`);
  }
}
