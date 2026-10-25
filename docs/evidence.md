# Evidências e matriz de capacidades

Consulta em 2026-10-24. “Observada” significa observada em código/relato externo,
**não testada nesta unidade**. “Documentada” significa descrita pelo mantenedor
ou fabricante. “Experimental” é hipótese ou dependência ainda não reproduzida.
“Indisponível” significa sem caminho demonstrado nesta investigação, não
impossibilidade. Todas as operações de dispositivo abaixo estão **não implementadas
neste projeto**. A CLI nega mutações sem autoridade externa; após esse gate,
o núcleo continua retornando `unsupported`. Nenhuma capacidade equivale a acesso total.

| Operação / requisito | Evidência externa | Fonte | Lacuna para Band do usuário |
| --- | --- | --- | --- |
| `miniapps.list` | Observada: serviço genérico lista apps; tipo pode ser UNKNOWN | G1, G4 | Identificação e classificação na unidade |
| `miniapps.install` | Observada: OTA tem caminho APP; desenvolvimento comunitário JS | G3, S2 | Pacote/modelo/versão e recuperação; não instala qualquer software |
| `miniapps.uninstall` | Observada: deleteApp e onAppDelete | G1, G4 | Sem confirmação de remoção/reconciliação nesta unidade |
| `watchfaces.list` | Observada: serviço próprio, requestWatchfaces | G2, G4 | IDs/códigos não são universais entre modelos |
| `watchfaces.install` | Observada: OTA diferencia WATCHFACE de APP | G3 | Pacote compatível e recuperação |
| `watchfaces.uninstall` | Experimental: deleção genérica usa app ID; classificação cruza listas | G1, G4 | Comportamento de watchfaces stock/custom e limites não ensaiados |
| `resources.install` | Observada: tipos RES/FONT existem no protocolo Huami | G6 | Tipo genérico não prova suporte da unidade; validação de pacote pendente |
| `firmware.official` | Observada: OTA valida pelo helper e modelo; enum comenta empacotamento MB7 | G3, G5, G6 | Original compatível, autenticidade, backup/restauração e autorização |
| `firmware.native` | Indisponível: sem prova reproduzível de boot próprio e restauração | S1; plano recovery | Bootloader, memória, assinatura, debug e drivers desconhecidos |
| `sensors.read` | Documentada: sincronização/HR em dispositivos Zepp OS; disponibilidade varia | S3 | Não implica todos sensores ou acesso bruto; nenhum dado real lido |
| `interface.show` | Documentada: UI local JS/API1; dados dinâmicos relatados com Zepp | S2, S4, S5 | Canal independente Android Rust → miniapp não implementado |
| `interface.events` | Experimental: retorno de toque pelo canal proposto | S4 | Sem prova de ida e volta deste terminal |
| `interface.vibrate` | Documentada: API local VIBRATE 1.0 | S6 | Controle remoto ainda sem adaptador |
| `interface.image` | Experimental: recursos locais existem; streaming/framebuffer não provado | S2, S7, G5 | Screenshot desabilitado no coordenador MB7; API3 não é API1 |
| `band.exec` | Indisponível: execução nativa arbitrária não demonstrada | Plano recovery | Console Android não significa shell na Band |

## Fontes de código fixadas

Gadgetbridge: referência fixada em a0948ee1cbc2a870f91d313f8e37df5f524465f7.
As interpretações offline derivadas e suas licenças são registradas em
[PROVENANCE.md](../PROVENANCE.md); não são atribuídas exclusivamente ao projeto.

- **G1** — [ZeppOsAppsService.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/zeppos/services/ZeppOsAppsService.java): `requestApps`, `parseAppList`, `deleteApp`; JS payload não implementado ali.
- **G2** — [ZeppOsWatchfaceService.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/zeppos/services/ZeppOsWatchfaceService.java): `requestWatchfaces`, `parseWatchfaceList`, `setWatchface`; comentários alertam sobre códigos vindos de outros modelos.
- **G3** — [ZeppOsFirmwareUpdateOperation.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/zeppos/operations/ZeppOsFirmwareUpdateOperation.java): `doPerform` chama validação; atualização distingue pós-instalação APP e WATCHFACE.
- **G4** — [ZeppOsSupport.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/zeppos/ZeppOsSupport.java): montagem de lista de apps e `onAppDelete` → `appsService.deleteApp`.
- **G5** — [MiBand7Coordinator.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/huami/miband7/MiBand7Coordinator.java): seleção de device sources e CRCs; `supportsScreenshots` retorna false. Isto não prova impossibilidade de outra interface de imagem.
- **G6** — [HuamiFirmwareType.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/HuamiFirmwareType.java): classes firmware/recursos/apps; comentário específico de empacotamento MB7.

Esses serviços são referências de investigação, não especificação suficiente
para emitir comandos reais. Ainda faltam encadeamento de autenticação,
negociação de versão, framing, confirmação e capturas autorizadas.
Os arquivos consultados declaram AGPL-3.0-or-later; revisar obrigações antes de
derivar implementação, sem assumir que mudar a linguagem elimina a licença.

## Fontes documentais

- **S1** — [Teardown da Band 7 comum, Cao Shuncheng, 2022](https://www.eefocus.com/video/520639.html): identifica DA14706; não demonstra estado de secure boot nem pinout recuperável desta unidade.
- **S2** — [Basics of Mi Band 7 development, autor das ferramentas, 2023](https://mmk.pw/en/posts/sb7_dev/) e [zmake](https://github.com/melianmiko/zmake): miniapps/watchfaces JS e recursos em pacotes; referência API1. Não são substituição do firmware integral.
- **S3** — [Gadgetbridge: Xiaomi Mi Band 7](https://gadgetbridge.org/gadgets/wearables/xiaomi/#mi-band-7) e [Zepp OS](https://gadgetbridge.org/basics/topics/zeppos/): modelo/protocolo distinto do Pro; recursos variam por aparelho. A página cita versões de firmware testadas, não identifica o aparelho do usuário.
- **S4** — [hmBle API1](https://docs.zepp.com/docs/1.0/reference/device-app-api/hmBle/): comunicação Device App ↔ Side Service. Não especifica serviço GATT independente pronto para cliente Rust.
- **S5** — [WatchDrip Band7](https://github.com/ddamT3/watchdrip-zepp-band7) e [Band 7 com Zepp](https://mmk.pw/en/posts/mi-band-7-with-zepp/): relatos comunitários com Zepp intermediário; dados dinâmicos não provam framebuffer. O fork documenta target internacional e versão de CLI específica; não tratado como suporte oficial ou universal.
- **S6** — [VIBRATE API1](https://docs.zepp.com/docs/1.0/reference/device-app-api/hmSensor/sensorId/VIBRATE/): fonte primária para vibração local; controle remoto não decorre automaticamente dela.
- **S7** — [Zepp OS 3.0](https://docs.zepp.com/docs/guides/version-info/new-features-30/): Canvas, transferência de arquivos e conversão de imagens pertencem a documentação posterior; não atribuí-los à Band7/API1.

## Complemento: instalação, Toolbox e limites da rota inicial

Fontes reconsultadas após a pesquisa do usuário, em 2026-10-24:

- **S8** — [Guia específico de instalação, 2023](https://mmk.pw/en/posts/sb7-install-guide/): miniapps/mostradores BIN instalados via Android; cita Toolbox para remoção. Registra limitações de menu, execução em segundo plano e acesso ao telefone. É procedimento comunitário histórico, não ensaio na unidade atual nem garantia de segurança do instalador.
- **S9** — [ZMake: guia do autor](https://mmk.pw/en/zmake/guide/): builder comunitário para Band7, geração de BIN e formato gráfico específico. A configuração deve corresponder ao modelo; o levantamento inicial de24/10 era documental; o builder ZMake foi posteriormente executado no commit fixado, com resultados atuais em [validation.md](validation.md).
- **S10** — [Toolbox: documentação do autor](https://mmk.pw/en/zepp/toolbox/): remoção local pode deixar componentes no telefone e requer reinício para atualizar o menu. Gerência remota é beta; o autor relata instabilidade de memória. Temporizador especial não comprova daemon geral. Não seguir recomendações de reset automaticamente.
- **S11** — [Gadgetbridge: limitações](https://gadgetbridge.org/basics/topics/zeppos/#missing-features): gerência experimental pode remover apps de sistema e não atualizar UI. Isso fundamenta bloqueio de artefatos de sistema/desconhecidos e reconciliação, não autorização para testar remoção indiscriminada.

Toolbox clonado e lido no commit
`8bf1a4b39121f34a9cc48fec879ccd2757d17cc9`:

| Fonte fixada | Evidência observada | Limite |
| --- | --- | --- |
| [README e licença](https://github.com/melianmiko/ZeppOS-Toolbox/blob/8bf1a4b39121f34a9cc48fec879ccd2757d17cc9/README.md) | Projeto declara Apache 2.0; ferramentas de arquivos/apps | Rever avisos de dependências/assets antes de reutilizar |
| [AppEditScreen.js](https://github.com/melianmiko/ZeppOS-Toolbox/blob/8bf1a4b39121f34a9cc48fec879ccd2757d17cc9/page/AppEditScreen.js) | `uninstall()` remove árvores de app/dados e arquivos externos declarados | Não equivale à operação BLE nem é uma política segura para copiar |
| [RemoteManScreen.js](https://github.com/melianmiko/ZeppOS-Toolbox/blob/8bf1a4b39121f34a9cc48fec879ccd2757d17cc9/page/RemoteManScreen.js) | Usa MessageBuilder e handler sobre armazenamento | Não demonstra GATT independente, root ou processo nativo |

Atualização da matriz: remoção local de miniapps ganha evidência específica S8/S10
além do serviço G1/G4. Gerência de arquivos ganha evidência documental/local e
remota experimental, mas permanece fora das 15 operações e não anunciada.
Nenhuma capacidade foi promovida a comprovada no aparelho do usuário.
A decisão e os critérios de teste estão no [roteiro prioritário](roadmap.md).
