# Três capacidades preparadas offline

`src/zepp_capabilities.rs` prepara requisições e decodifica respostas. Não abre
Bluetooth, não autentica, não envia frames, não concede autorização e não habilita
operações do terminal/MCP. Todo comando informa `offline_only:true`,
`executable:false` e `physical_support_verified:false`.

| Categoria | Endpoint / payload | Resposta / limites | Efeito e gate |
| --- | --- | --- | --- |
| Bateria proprietária | 0x0029 / 03 | 04, 21 bytes; percentual 0–100; estado de carga e bytes temporais preservados | leitura, sessão autenticada; não substitui bateria GATT já física |
| Localizar / parar | 0x001a / 03 ou 06 | ACK 04 ou parada pelo aparelho 07; trailer preservado até 32 bytes | vibração potencial, consentimento adicional antes de enviar; ACK não prova atuação do motor |
| Recursos de notificações | 0x001e / 01 | 02, versões de serviço 4/5; flags e capacidades opacas preservadas; máximo local 256 bytes opacos | leitura; nenhuma notificação pessoal capturada/enviada |

As versões 4/5 acima são do serviço de notificações, não da API Zepp OS. A Band7
continua alvo API1.0. A data da última carga é mantida como oito bytes do relógio;
não é convertida para UTC/Unix sem semântica/fuso comprovados. Dados ausentes não
viram 50% como no fallback upstream; truncamento é erro. Campos não reconhecidos
permanecem desconhecidos, sem criar suporte fictício.

O `ServiceEncryption` consome o mapa já decodificado pelo ServiceListProbe Java.
Serviço ausente, flag desconhecida ou discovery ausente impedem framing. Quando
explicitamente plaintext, a simulação usa o codec Huami existente; se encrypted,
retorna EncryptedFramingRequired, nunca rebaixa a plaintext. A ligação ao transporte
criptografado Java ainda falta. Discovery sintético não comprova autorização.

Fontes primárias fixadas em Gadgetbridge
`a0948ee1cbc2a870f91d313f8e37df5f524465f7` (AGPL-3.0-or-later):

- [Huami2021Service](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/huami/Huami2021Service.java): IDs e comandos.
- [ZeppOsSupport](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/zeppos/ZeppOsSupport.java): requestBatteryInfo, handleBattery, sendFindDeviceCommand, handleFindDevice e writeToChunked2021. A cifragem real deriva da lista de serviços, não do booleano ignorado do chamador.
- [HuamiBatteryInfo](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/HuamiBatteryInfo.java): layout da bateria.
- [ZeppOsNotificationService](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/zeppos/services/ZeppOsNotificationService.java): capability request/response.

O coordenador Zepp OS não anuncia localizar contínuo para esse caminho; não
implementamos loop nem prometemos duração de vibração. Limites 32/256 são política
local defensiva, não restrições físicas demonstradas. Todos os testes são fixtures
sintéticas, não capturas nem prova de interoperabilidade.

Reproduzir: `cargo test --offline --test zepp_capabilities` e
`cargo run --offline --example prepare_zepp`. Quatro testes cobrem payloads, gating
por discovery/cifragem, respostas válidas, truncamento, versões, limites e flags.
Regressão total: 73 testes PASS, rustfmt e clippy PASS. Android/ART desses novos
módulos e envio físico: NOT_RUN. Próximo gate: ligar despacho autenticado e consulta
somente leitura; haptic exige autorização física separada e controle de parada.

## Integração posterior de bateria

A preparação Rust acima continua sem IO. A consulta Java foi posteriormente ligada
ao transporte como ação explícita: [battery-query-0.6.md](battery-query-0.6.md).
A distinção host/ART/hardware e os resultados da rodada estão nos recibos; não
confundir o sender de bateria com implementação de haptics ou notificações.
