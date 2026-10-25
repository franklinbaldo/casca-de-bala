# Primeiro componente de recepção Android/BLE — offline

Implementação experimental offline; não autentica nem acessa o dispositivo.

## Escolha baseada em código

Fonte primária: Gadgetbridge no commit
`a0948ee1cbc2a870f91d313f8e37df5f524465f7`, já usado no mapa de evidências:

- [Huami2021ChunkedEncoder.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/Huami2021ChunkedEncoder.java): MTU-3, cabeçalhos inicial/continuação, tamanho little-endian, endpoint e flags; cifra altera comprimento/payload.
- [Huami2021ChunkedDecoder.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/Huami2021ChunkedDecoder.java): reassembly, flags e seleção do header estendido.
- [ZeppOsSupport.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/zeppos/ZeppOsSupport.java): force2021Protocol retorna true; seleção de criptografia por endpoint e roteamento após autenticação.
- [HuamiSupport.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/HuamiSupport.java): InitOperation2021, callbacks chunked e sendChunkedAck.

O decoder novo é receptor **somente plaintext estendido**, sem GATT, UUID,
transmissão, geração de ACK, criptografia ou autenticação. Endpoint numérico
na mensagem remontada é informação de roteamento, não uma capacidade autorizada.
Não está conectado à CLI/Android/MCP e não altera capability discovery.
Não executamos Gadgetbridge nem simulamos dispositivo; implementamos e testamos
Rust sobre vetores sintéticos. Não há capturas reais ou compatibilidade física
comprovada. Arquivos de referência declaram AGPL-3.0-or-later; módulo marca SPDX,
atribuição ao autor e implementação Rust com validação adicional. Texto da licença
preservado em [Gadgetbridge-AGPL3.txt](licenses/Gadgetbridge-AGPL3.txt). Não presumir
que esta referência pode ser incorporada em distribuição proprietária.

## Framing suportado

| Offset | Conteúdo observado no formato estendido |
| --- | --- |
| 0 | 0x03, data chunk |
| 1 | flags: bit0 primeiro, bit1 último, bit2 pede ACK, bit3 cifrado |
| 2 | byte estendido (perfil aceita somente zero) |
| 3 / 4 | handle / sequência de fragmento |
| 5–8 no primeiro | comprimento declarado u32 LE |
| 9–10 no primeiro | endpoint u16 LE |
| 11 no primeiro / 5 nos demais | começo do payload |

O parser rejeita flags desconhecidas, frames não-data e qualquer mensagem
cifrada (sem fallback para plaintext). needsAck é metadado no resultado, não
confirmação de operação/tela/instalação e não instrui envio automático.
ACK 0x04 pertence a outro tipo e é rejeitado. O formato legado de header de
4 bytes não está implementado.

## Estado e políticas locais

Um Receiver por geração de conexão; nova geração exige nova instância.
Recebe callbacks com a geração do host para recusar eventos antigos sem destruir
mensagem da sessão atual. Uma mensagem incompleta por instância; handle e sequência
contígua obrigatórios, início em zero, sem wrap. Duplicata, frame fora de ordem,
novo início sobre mensagem parcial e erro de tamanho limpam a remontagem.
Não há fila/retry nem emissão de comando mutante.

Limites conservadores do host: 4096 bytes por mensagem, 512 por notificação,
sequências 0..255, payload não vazio. Não são limites comprovados da Band nem
MTU negociado. Mensagem final só sai com comprimento exato. Uma mensagem completa
não é deduplicada por handle; handles podem ser reutilizados e o protocolo de
aplicação terá de resolver duplicatas.

Deadline absoluto 5000 ms desde o primeiro fragmento; novos fragmentos não o
renovam. Host fornece relógio monotônico e chama expire mesmo sem callbacks.
Expiração ou relógio regressivo limpa parcial e retorna erro. Sem timer/thread
interno: adaptador Android/Tokio futuro deve agendar essa chamada. A geração
também é responsabilidade do host, não token de autenticação. Reconexão não
retoma bytes antigos. Políticas são mais estritas que o Java de referência e
podem rejeitar tráfego que ele tolera; isso exige validação por captura autorizada.

## Reproduzir e provas faltantes

```sh
# Execute na raiz do projeto
cargo test --offline --test huami_chunk
cargo test --offline
cargo fmt --all -- --check
cargo clippy --offline --all-targets -- -D warnings
```

Vetores em tests/huami_chunk.rs são texto remoto reproduzível: cabeçalho LE,
fragmentação, ACK metadata, comprimentos, flags/cifra, duplicatas, ordering,
limites, sequência completa, timeout, relógio e gerações de conexão. Nenhuma
fixture binária ou identificador pessoal. PASS destes testes prova somente
comportamento do componente offline.

**Bloqueio técnico da ponte independente:** a documentação primária
[hmBle API1](https://docs.zepp.com/docs/1.0/reference/device-app-api/hmBle/)
descreve Device App ↔ Side Service. Não estabelece mapeamento de um endpoint
Huami genérico até nosso miniapp. Serviços JS no Gadgetbridge já consultados
não fornecem essa prova. Autenticação/cifra, negociação dos serviços, callbacks
Android e semântica do payload do miniapp continuam não implementados.

Próxima prova necessária: unidade identificada, captura autorizada e sanitizada
que mostre conexão/autenticação e notificação real; confirmar headers, flags,
MTU, sequência e cifra. Depois demonstrar cartão enviado pelo Android próprio e
toque recebido dele, com evidência da execução no miniapp. Rota Zepp/Side Service
é ensaio separado. Não adquirir chaves, parear, instalar/flash/reset ou ampliar
acessos nesta entrega. Protocolo completo e instalação física: NOT_RUN;
installable:false permanece no recibo do candidato.
