# 0.6: sessão de protocolo para desbloquear capacidades

Base remota verificada:78bab64f057c6bf511ffdf4226ec53670278ec51; nenhuma PR aberta
na consulta. Sem AGENTS.md/.agents/skills no checkout canônico. Cópia local isolada;
nenhuma publicação, credencial, pareamento, leitura de saúde ou escrita BLE.
O patch Pillow13 permanece separado e não foi incorporado aqui.

## Estado e matriz de capacidades

D=interface documentada/implementada upstream; I=implementação no Casca;
S=teste sintético local; H=evidência física da unidade; ?=desconhecido/não testado.
D não significa disponibilidade universal no firmware da unidade. A identificação
relatada é Band7 comum, software2.2.0.1, hardware0.91.130.5; ProductID103 não é
alvo/deviceSource263. Indicadores derivados não são sensores brutos.

| Capacidade | D | I / S no Casca | H da unidade / gate |
| --- | --- | --- | --- |
| Descobrir/vinculados, identificação DIS/PnP/bateria padrão | Android/GATT e evidência sanitizada | Android real + testes host | leitura0.3 por captura e bateria/snapshot0.4 por relato; não valida auth |
| RSSI conectado | Android readRemoteRssi | radar contínuo0.5 + S | valores/UX0.5 ainda ?; não fornece posição/direção |
| Sessão Huami2021/auth | InitOperation2021, HuamiSupport | crypto BC1.86, framing cifrado, máquina de estados, JNI/ART com fixtures; sem autenticação física | ?; link cifrado e vínculo Android não bastam |
| Consulta de estado/bateria no protocolo proprietário | ZeppOsSupport.requestBatteryInfo | preparação 0x0029/03 e decoder21 bytes, S; sem envio; bateria GATT é outro caminho | ?; primeiro alvo após auth validada |
| Histórico atividade/passos/sono | FetchActivityOperation/Activity8 | decoder, SQLite, retomada/JNI; S | ?; falta fetch autenticado e semântica de categorias; sono é indicador |
| HR/SpO2 históricos | FetchHeartRate*/FetchSpo2NormalOperation | HR6/SpO2 decoders + persistência onde já definida; S | ?; não são ECG/PPG brutos e não implicam coleta completa |
| HR/passos ao vivo | ZeppOsSupport handlers/comandos | não implementado no Android | ?; exige protocolo, permissões/consentimento por coleta |
| Exercícios e demais métricas | parsers/fetches upstream por categoria | inventário; cobertura local parcial | ?; não assumir todas modalidades, temperatura ou sinais brutos |
| Localizar/vibrar | ZeppOsSupport.onFindDevice + HuamiSupport loop | preparação start03/stop06 e ACK/evento em Rust, S; despacho físico continua unsupported | ?; exige sessão inicializada e protocolo; não é simples escrita GATT livre |
| Alarmes e configurações | ZeppOsAlarmsService/ZeppOsConfigService | consultas/listagens e mutações não implementadas | ?; listar/ler antes de autorizar qualquer mutação |
| Notificações | ZeppOsNotificationService | consulta de capacidades 0x001e/01 e decoder v4/v5, S; enviar mensagens não implementado | ?; envio controlado e sem capturar notificações pessoais por padrão |
| Listar/instalar/remover miniapps | ZeppOsAppsService e operação de atualização | contratos/offline; miniapp candidato installable:false | ?; listar/API por firmware antes de instalação/remoção, proteger apps de sistema |
| Listar/instalar/selecionar mostradores | ZeppOsWatchfaceService/OTA | contratos/offline | ?; remoção específica ainda precisa comprovação; não inferir de remover miniapps |
| Recursos e OTA oficial | HuamiFirmwareType/ZeppOsFirmwareUpdateOperation | inspeção/contratos, sem transporte | ?; backup/recuperação/autorização separados; fora da0.6 |
| Miniapp API1 e pequenos programas | miniapp JS/ZMake; VM local experimental | mock/inspeção; sem instalação | ?; hmBle/SideService não prova canal Android próprio→miniapp |
| Firmware nativo próprio | investigação; sem cadeia pública reproduzível de boot/restauração nesta unidade | nenhum boot implementado | ?; trilha desejada preservada, sem declarar impossível ou pronta |

Fontes primárias fixadas em Gadgetbridge a0948ee1cbc2a870f91d313f8e37df5f524465f7:
[InitOperation2021](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/operations/init/InitOperation2021.java),
[ZeppOsSupport](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/zeppos/ZeppOsSupport.java),
[MiBand7Coordinator](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/huami/miband7/MiBand7Coordinator.java).
Serviços/fetches e limitações por dado: docs/evidence.md, docs/data-coverage.md.

## Primeira fatia vertical e dependências

Meta0.6: seleção explícita → conexão → AUTH verificada → uma consulta de estado
proprietário → resposta tipada → desconexão limpa. Consulta de bateria/estado não
substitui o leitor GATT existente; prova a nova rota antes de coletar saúde.
Não declarar acesso total a partir dessa fatia. Em seguida: um lote Activity8
pequeno até SQLite/reabertura, com consentimento separado para dados pessoais.

1. Confirmar mapeamento de serviços/características e framing/ACK por fonte e
   discovery real autorizado; não enviar no escuro UUIDs/comandos de outra família.
2. Escolher/revisar implementação compatível de ECDH-B163 e AES. O upstream tem
   ECDH_B163 Java (derivado tiny-ECDH, com suas ressalvas); não criar curva/cifra
   própria nem chamar o código automaticamente de auditado. Testar vetores
   públicos/sintéticos e conversões24/48/16 bytes antes de integrar chaves reais.
3. Definir referência local de credencial já pertencente ao usuário e armazenamento
   Android. Nenhuma chave entra no Rust serializado, chat, Git ou log. Obter nova
   credencial/extrair de outro app não está autorizado. Sem essa via segura,
   autenticação física permanece bloqueada, com motivo claro.
4. Controlador de handshake: estado/geração, uma operação em voo, cancelamento,
   timeout e falha de prova fecham a sessão; peer success sozinho não autoriza.
   Reconectar exige geração e prova novas, sem reutilizar material de sessão.
5. Após auth, negociar/descobrir serviços e seleção de cifra por endpoint antes
   da consulta. O Receiver atual rejeita cifra explicitamente; não alimentar
   ciphertext como Activity8 ou supor que todos endpoints usam a mesma cifra.

Cuidado: InitOperation2021 upstream liga notificações, faz handshake e depois
acerta relógio/executa fase2/fase3. Não portar a sequência completa: nosso teste
não pode alterar hora, perfil ou configurações por efeito colateral. Auth exige
escritas de protocolo/CCCD; isso é diferente de prometer teste somente leitura
GATT. O ensaio físico precisará autorização específica desse fluxo, ainda futura.

## Preparação local concluída

src/huami_auth.rs decodifica somente payload AUTH já reassemblado selecionado no
endpoint0x0082: desafio67 bytes (cabeçalho+nonce16+chave pública48), resultado de
sucesso/bad-key3 bytes. Comprimentos exatos, endpoint, header e códigos
inesperados são recusados. Nenhum UUID/GATT writer, ECDH/AES, credencial ou
máquina de auth foi implementado. Chave recebida é explicitamente não validada;
PeerReportedSuccess não é prova local. Tipos não têm Debug/Serialize de payload.

TDD: RED por módulo ausente; GREEN4 testes de envelope/reassembly sintéticos,
incluindo todas truncagens, extensões, códigos desconhecidos, geração antiga e
ciphertext rejeitado. Regressão cargo test62 testes, fmt e clippy:PASS.
Android/ART/BLE/auth/saúde física:NOT_RUN. Não houve APK novo ou publicação.
O ensaio RED de UX iniciado antes da mudança de prioridade foi preservado fora
do produto em handoff/radar-deferred; não constitui teste aprovado desta fatia.

## Aceite futuro com Franklin

- Sem chave disponível/consentimento: explicar bloqueio; preservar leitor básico.
- Autorizar somente handshake e consulta definida, sem reset/despareamento ou
  extração de chave, sem coleta de saúde. Não pedir segredo pelo chat.
- Conexão/AUTH/sucesso comprovado/consulta são estados distintos. Timeout ou chave
  inválida não produz sucesso, retry infinito, mudança de horário ou outro comando.
- Após desconectar, chaves de sessão descartadas; reconexão precisa nova prova.
- Quando autorizar coleta: lote pequeno, armazenamento apenas local, proveniência,
  lacunas e duplicatas verificáveis; exportação por escolha explícita posterior.
- Localizar/vibrar só entra depois de provar essa sessão e obter autorização da
  mutação. Instalações, notificações, firmware e acesso bruto seguem gates próprios.

## Incremento local após o gate Activity

Ver [zepp-capabilities-0.6.md](zepp-capabilities-0.6.md) para as três categorias
preparadas e [cancel-resolution-0.6.md](cancel-resolution-0.6.md) para o teste
focalizado instalado. Leituras proprietárias exigem sessão/autenticação e resolução
de cifra por serviço antes do envio. Alterações de alarmes/configurações e envio de
notificações exigem autorização de escrita distinta. Histórico/sensores exigem escopo
de dados/consentimento local; OTA e firmware próprio exigem gates de recuperação.
Nenhuma capacidade proprietária nova foi verificada na unidade nem habilitada no
terminal. A versão de protocolo de notificações não altera a API1.0 dos miniapps.

Preparação de produto para o próximo gate: [product-pilot-0.6.md](product-pilot-0.6.md).
Entrada de credencial somente local/efêmera, sem nova capacidade física comprovada.
