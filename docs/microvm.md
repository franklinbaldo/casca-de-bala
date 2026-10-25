# B7VM v1 — micro-VM experimental para Band 7

**Estado:** protótipo offline, não instalado. Não é protocolo BLE Xiaomi/Zepp OS,
firmware nativo ou formato MCP. Não há canal Android ↔ miniapp implementado.
O executor JS não foi empacotado nem testado na API1 física: conferir suporte a
arrays, operadores bitwise e orçamento de execução antes da implantação. Não anexar este
runner à página real antes do build API1.

## Envelope binário

Inteiros multibyte em little-endian.

| Offset | Bytes | Semântica |
| --- | --- | --- |
| 0 | 4 | ASCII B7VM, hex 42 37 56 4d |
| 4 | 1 | Versão 01 |
| 5 | 1 | Reservado 00 |
| 6 | 2 | Contagem de instruções u16 LE (2–32) |
| 8 | 5 × count | Instruções de comprimento fixo |

Cada instrução tem opcode u8 e operando i32 LE. Apenas PUSH_I32 pode ter
operando diferente de zero. Comprimento máximo 168 bytes. Sem padding,
trailers ou múltiplos programas.

| Opcode | Instrução | Semântica |
| --- | --- | --- |
| 00 | HALT | Somente no final; exatamente 1 elemento na pilha |
| 01 | PUSH_I32 | Insere operando i32 |
| 02 | ADD | a + b com wrap i32 |
| 03 | SUB | a - b com wrap i32 |
| 04 | MUL | a * b com wrap i32 |
| 05 | DUP | Duplica topo |
| 06 | DROP | Remove topo |
| 07 | GT | a > b: 1; senão 0 |

Pilha limitada a 16 valores, máximo de 32 instruções e nenhum loop, salto,
IO, sensor, vibração, rede, acesso a arquivos, carregamento de código dinâmico
ou operação persistente. Programas malformados, opcodes desconhecidos, underflow
e overflow são rejeitados. Não existe uma instrução para executar Bash.

JavaScript usa aritmética i32 e multiplicação exata via partes de 16 bits,
sem depender de Math.imul.
Rust usa wrapping_add, wrapping_sub e wrapping_mul. O arquivo
[vetores compartilhados](../miniapp/vm/vectors.json) fixa programas e resultados
para testes diferenciais JS/Rust.

## Código e execução offline

- Rust: [codec, validador e executor de referência](../src/microvm.rs).
- JavaScript: [interpretador standalone](../miniapp/vm/runner.js), sem eval/Function.
- Testes: [suíte JS](../miniapp/tests/vm.test.mjs) e módulo de testes Rust.
- Medição: [benchmark somente de bytes](../bench/size.mjs), para JSON versus B7VM
  representando os mesmos programas.

Executar na raiz do crate:

    node --test miniapp/tests/page.test.mjs miniapp/tests/vm.test.mjs
    node bench/size.mjs
    cargo test --offline
    cargo fmt --all -- --check
    cargo clippy --offline --all-targets -- -D warnings

Este benchmark não compara FlatBuffers, Protobuf, CBOR ou MessagePack: requer
implementações reais dessas bibliotecas para cargas semanticamente equivalentes.
Não inferir latência BLE, compatibilidade na Band 7, consumo de energia ou
vantagem universal sobre JSON a partir de tamanhos de mensagens.

## Gates seguintes

1. Compilar o pacote miniapp API1, fixar versões e testar suporte JS no alvo
   Xiaomi Smart Band 7 comum, sem presumir o alvo da variante Pro.
2. Começar com programas embutidos no pacote, sem comunicação dinâmica.
3. Só habilitar transferência Android → miniapp com canal autenticado e
   comprovado (a API hmBle não é GATT público).
4. Reproduzir vetores no aparelho, medir memória, latência e consumo.
5. Especificar autorização e orçamento por capacidade antes de adicionar
   sensores, persistência, vibração ou novos efeitos.
