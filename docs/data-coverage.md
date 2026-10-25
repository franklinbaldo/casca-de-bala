# Cobertura de dados e ingestão local — requisito-alvo completo

25/10/2026. Coletar o conjunto completo disponibilizado pela unidade é requisito,
não resultado atual. Nenhum dado real foi lido. Fonte primária desta matriz:
Gadgetbridge `a0948ee1cbc2a870f91d313f8e37df5f524465f7`, arquivos abaixo lidos.
MiBand7Coordinator herda ZeppOsCoordinator; isso mostra caminhos de implementação,
não cobertura universal por firmware/unidade. A existência de enum de fetch não
prova que a Band7 produza todos os tipos. Estado físico de todas as categorias:
**desconhecido**. Coleta Android/end-to-end: **não implementada/não testada**.

## Matriz por categoria

| Categoria | Histórico / ao vivo observado na implementação | Natureza, granularidade e unidades | Tempo, origem e lacunas |
| --- | --- | --- | --- |
| Atividade e passos | FetchActivityOperation; handle2021Steps em ZeppOsSupport | Histórico 8 bytes/minuto no perfil Zepp OS: kind, intensity, passos u8, HR u8, unknown1, sleep/deep/REM. Ao vivo tem outro payload. Passos são contagem reportada, intensidade é código; não acelerometria bruta | Primeiro timestamp vem do fetch, incremento de um minuto. Decoder desta entrega só implementa histórico já desempacotado; não somar bytes como total diário sem validar semântica |
| Batimento | HR no histórico de atividade, FetchHeartRateManual/Resting/Max; handle2021HeartRate | HR em bpm/indicadores; repouso é derivado, máximo é estatística, não ECG/RR/PPG bruto. Resting usa registros de 6 bytes: epoch u32 LE, offset i8 em quartos de hora, HR u8 | Frequência/retencão configuráveis e dados podem faltar. Atividade preserva HR code 0/255 sem inventar sentinel. Fetch de repouso traz offset explícito; decoder histórico HR6 de repouso/máxima agora implementado offline, sem banco ou fetch |
| Sono | Campos de atividade + HuamiExtendedSampleProvider | Estados/indicadores derivados, resolução base de minuto; provider aplica heurísticas a deep/REM, não medidas clínicas nem sinal bruto | Horário depende de fetch e offset; sessões atravessam meia-noite/DST. Nesta entrega preservamos bytes, sem copiar classificações/heurísticas |
| SpO2 | FetchSpo2NormalOperation manual/automático; tipo SPO2_SLEEP separado | Normal versão 2: blocos 65 bytes após versão; epoch i32 LE, valor com bit automático e 60 bytes desconhecidos. Percentual derivado, não PPG. Cadência não assumida | Desconhecidos não promovidos a amostras adicionais. Normal/sleep têm caminhos e lacunas diferentes; decoder de SpO2 normal v2 agora implementado offline; tipo sleep continua separado |
| Exercícios | FetchSportsSummary/Details, ZeppOsActivitySummaryParser e DetailsParser | Resumos e trilhas/eventos: HR, velocidade/cadência, coordenadas quando fornecidas. Granularidade variável; parser contém TODOs (ex.: séries de força). GPS não implica receptor GPS interno na Band7 | Timestamps absolutos/deltas e unidades específicas do parser; não reinterpretar tudo como amostra por minuto. Telefone pode fornecer posição; origem precisa ser preservada |
| Bateria e estado | ZeppOsSupport handle2021Battery e HuamiBatteryInfo | Snapshot de nível %, estado carregando/normal e tempos de carga quando presentes. Não é sensor de saúde nem histórico completo de energia | Registrar instante de observação do host separadamente do tempo reportado. Código legado usa fallback 50 em ausência: não copiar esse valor como dado válido |
| Stress e PAI | FetchStressManual/Auto e FetchPai; capacidades ZeppOsCoordinator | Indicadores derivados e scores, não cortisol ou sinal bruto. Cadência/unidades dependem de cada formato | Sem inventar periodicidade de ausência; decoders locais não implementados |
| Respiração durante sono | FetchSleepRespiratoryRate, flag supportsSleepRespiratoryRate | Indicador derivado; não fluxo respiratório bruto. Histórico próprio | Existência de provider/tipo não confirma cobertura individual. Granularidade/sentinels exigem decoder específico e ensaio |
| VO2Max, estatísticas | Coordenador e parsers de resumo/WorkoutVo2MaxSampleProvider; FetchStatistics | Estimativa derivada de exercício, unidades/qualidade conforme modalidade; não medição laboratorial | Não concluir que existe todo dia ou para toda modalidade; ingestão ainda não implementada |
| Temperatura / sinais brutos / demais sensores | FetchTemperature existe na família; não demonstrado para Band7 comum. Nenhum caminho bruto completo verificado aqui | Não anunciar temperatura, ECG, PPG bruto, acelerômetro/giroscópio contínuos como disponíveis. API local de miniapp não equivale a exportação BLE histórica | Estado Unknown, não Unsupported por inferência. Requer prova específica antes de declarar suporte ou ausência universal |

## Escopo concreto implementado

`src/ingestion.rs` decodifica exclusivamente o payload histórico **Activity8**
com origem tipada (referência local do dispositivo, firmware declarado, indicador
sintético). Todos os oito campos são preservados; prefixo/code significa código
de protocolo, não sinal fisiológico bruto. O tamanho 8 e a cadência de 60 segundos
vêm de ZeppOsSupport.getActivitySampleSize e FetchActivityOperation.
O CRC de atividade na fonte ainda é TODO; este decoder não afirma integridade ou
autenticidade, nem valida framing/checksum. Entrada deve vir de etapa autenticada,
reassemblada e de fetch corretamente selecionado, ainda não implementada.

Caller fornece primeiro instante Unix em segundos e offset UTC opcional em
segundos, resolvidos do cabeçalho do fetch/relógio da unidade. Não usamos fuso
atual do host. Offset não substitui timezone IANA nem resolve DST. Instantes são
preservados sem truncar para dia; firmware/fuso/correções de relógio exigem
reconciliação. Perfil suporta timestamps não negativos e aritmética verificada.

Availability distingue Available, Absent (payload vazio recebido), Unsupported
(incompatibilidade confirmada), Unimplemented (decoder/coletor ainda ausente),
Unknown (capacidade não demonstrada). O decoder só produz Available/Absent ou
erro. Pacote inválido, falta de permissão, desconexão e ausência de transporte
não viram Absent. Payload vazio não prova que sensor inexiste ou que não há
histórico na unidade. Não há lista de capabilities sensíveis anunciada por isso.

`Window` é armazenamento de referência **em memória**, limitado a 4096 minutos
por origem/firmware/perfil, sem despejar valores em Debug ou serialização MCP.
Importa lote atomicamente: duplicata idêntica não acrescenta, conflito no mesmo
instante não sobrescreve e desfaz lote; mistura de origem/fase de minuto é erro.
Não há descarte silencioso na capacidade máxima. Reimportação após reconectar
pode sobrepor janela anterior; dados atrasados preenchem gaps.

`missing_minutes(start,end)` recebe janela esperada explícita, fim exclusivo;
`resume_from` retorna primeiro minuto ausente, não maior timestamp recebido.
Isso detecta buracos na janela de sync, **não** prova falha de sensor/coleta.
Não usar esta cadência para SpO2/exercícios/leituras eventuais. O cursor não é
persistido aqui: Android deve transacionar registros + checkpoint antes de avançar,
reabrir overlaps e conservar proveniência/offsets. Nenhuma camada de banco foi
criada nesta etapa; o conjunto de dados da pulseira ainda não está coletado.

## Decoders adicionais — repouso, máximo e SpO2 normal v2

A rodada seguinte adicionou `src/health_samples.rs`, somente para **payloads
históricos já decifrados e separados por categoria de fetch**. A procedência,
o firmware e o sinalizador de fixture sintética permanecem em cada registro.
Nenhum decoder é anunciado como sensor conectado ou sincronização completa.

- `decode_heart_rate6`: registros de **6 bytes**, `u32 LE` de segundos Unix,
  deslocamento UTC como `i8` em quartos de hora e byte original de FC.
  `HeartRateKind::Resting` e `Maximum` impedem misturar as duas categorias.
  Valores 0 e 255 são preservados como códigos, sem declarar ausência ou BPM
  válido. O deslocamento não é convertido arbitrariamente em fuso IANA.
- `decode_spo2_v2`: exige header de versão **02**, seguido de registros de
  **65 bytes**: timestamp `i32 LE`, byte de SpO2 com bit alto indicando medição
  automática e os **60 bytes restantes preservados integralmente**. O low 7-bit
  é `percent_code`, não uma certificação clínica nem amostra de oxímetro
  bruto. Timestamp negativo é recusado por política do host.
- Limite de 4096 registros por chamada. Input sem header SpO2 válido retorna
  erro, não `Absent`. Payload HR vazio e SpO2 apenas com header 02 significam
  ausência **naquela resposta**, não inexistência de dados ou recurso.
  Categoria sem decoder continua `Unimplemented` e suporte físico continua
  `Unknown`, sem produzir `Unsupported` artificialmente.
- Estas funções não baixam dados, não autenticam BLE, não persistem em Android
  e não expõem informações ao MCP. O `Window` de atividade tem cadência
  de minuto e **não pode ser reutilizado** para estas amostras eventuais:
  requerem índices e checkpoints próprios, com persistência transacional.

Fontes de contrato verificadas no snapshot Gadgetbridge
`a0948ee1cbc2a870f91d313f8e37df5f524465f7`:

- [FetchHeartRateRestingOperation.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/operations/fetch/FetchHeartRateRestingOperation.java)
- [FetchHeartRateMaxOperation.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/operations/fetch/FetchHeartRateMaxOperation.java)
- [FetchSpo2NormalOperation.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/operations/fetch/FetchSpo2NormalOperation.java)

Código derivado identifica AGPL-3.0-or-later e autores da implementação
de referência. O formato comunitário não prova que a unidade física Band 7
produz todas as categorias em todas as versões. Testes:
`cargo test --offline --test health_samples`.

## Fontes fixadas e reprodução

Base dos caminhos dos arquivos de protocolo:
[fetch/](https://github.com/Freeyourgadget/Gadgetbridge/tree/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/operations/fetch),
[FetchActivityOperation.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/operations/fetch/FetchActivityOperation.java),
[ZeppOsSupport.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/zeppos/ZeppOsSupport.java),
[ZeppOsCoordinator.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/huami/zeppos/ZeppOsCoordinator.java),
[HuamiExtendedSampleProvider.java](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/huami/HuamiExtendedSampleProvider.java).
AGPL-3.0-or-later e atribuição preservadas no módulo, com texto em docs/licenses.

```sh
# Execute na raiz do projeto
cargo test --offline --test ingestion
cargo test --offline
cargo fmt --all -- --check
cargo clippy --offline --all-targets -- -D warnings
```

Fixtures sintéticas em texto em tests/ingestion.rs. Não executar código upstream.
Não há arquivo pessoal ou dado real neste trabalho.

## Privacidade e provas físicas faltantes

Escopo futuro de armazenamento é **local Android**. Criptografia em repouso,
retenção/exclusão, backup e permissões devem ser definidos antes de coleta real.
MCP futuro deve exigir categorias, intervalo, agregação e autorização explícitos;
nenhum histórico pessoal é enviado automaticamente a terceiros ou serviços externos.
Logs públicos não devem conter valores, identificadores BLE ou timestamps pessoais.
Ausência de Debug/Serialize reduz exportação acidental, mas não substitui controle
de acesso, política de logs e proteção do banco.

Próximos gates: identificar modelo/firmware/relógio sem publicar IDs; autenticação
BLE e fetch completos, revisão de criptografia/integridade, captura consentida
sanitizada e testes locais ponta a ponta por categoria. Comparar retencão, unidades,
contagens, lacunas e replay com origem rastreável. Gaps e sentinels exigem revisão
na unidade. Não parear/instalar/ler a pulseira nesta entrega; permissões sensíveis
Android também não foram configuradas. Coleta completa permanece **não demonstrada**.

Atualização de persistência: Activity8 agora tem [SQLite local](local-storage.md)
com transação de registros/checkpoint e reabertura testada no host. A Window em
memória permanece referência; o banco Android final e as outras categorias não
foram integrados. Isso não promove cobertura física nem coleta completa.

Evidência externa posterior: [diagnóstico GATT sanitizado](gatt-identification.md)
fornecido pelo usuário via nRF Connect confirma DIS/PnP e snapshot de bateria 91%.
HR 2A37 foi somente descoberto. Isso atualiza o conhecimento externo de bateria,
mas não comprova callback/leitura do nosso app nem cobertura de saúde/histórico.
