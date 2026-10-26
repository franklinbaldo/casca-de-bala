# Proveniência e licenças

Este projeto completo é distribuído sob **AGPL-3.0-or-later** (LICENSE).
A escolha não converte bibliotecas externas permissivas ou ferramentas GPL em
código autoral do projeto. Seus avisos/condições permanecem aplicáveis.
Não há afirmação de clean-room ou de autoria própria sobre derivações externas.

## Código com origem/referência externa declarada

Referência Gadgetbridge:
https://github.com/Freeyourgadget/Gadgetbridge/tree/a0948ee1cbc2a870f91d313f8e37df5f524465f7

- src/huami_chunk.rs: Huami2021ChunkedEncoder/Decoder; copyright2022–2024
  José Rebelo. Implementação Rust e validações locais preservam AGPL.
- src/ingestion.rs: FetchActivityOperation e seus contribuidores; interpretação
  Activity8, ingestão e limites locais. AGPL-3.0-or-later.
- src/health_samples.rs: FetchHeartRateRestingOperation,
  FetchHeartRateMaxOperation e FetchSpo2NormalOperation; contribuidores do
  Gadgetbridge. AGPL-3.0-or-later, sem alegação de leitura física universal.
- src/activity_db.rs, event_db.rs, ffi.rs, sync_session.rs e
  android-bridge/band7_jni.c: persistência e integração do conjunto, já marcados
  AGPL-3.0-or-later. Todos os oito avisos foram conservados.

O texto da licença upstream continua em docs/licenses/Gadgetbridge-AGPL3.txt.
Fontes/links por capacidade estão em docs/evidence.md e docs/data-coverage.md.
Código Java de app/políticas, contratos, UI/VM e scripts locais não são apresentados
como código do Gadgetbridge; sua inclusão sob AGPL acompanha a licença do projeto.
Ausência anterior de SPDX não foi usada para reclassificar qualquer trecho AGPL
como MIT. O header host JNI declara autoria local e base na tabela normativa;
Android compila contra jni.h do NDK, não contra cópia redistribuída desse header.

## Dependências e ferramentas

third_party/rust-licenses.json lista os 29 pacotes externos fixados no Cargo.lock,
licenças declaradas e cópias dos avisos disponíveis. Elas incluem MIT/Apache-2.0,
Zlib, Unlicense e Unicode-3.0. SQLite embarcado por libsqlite3-sys conserva seu
aviso de domínio público em third_party/SQLITE-NOTICE.txt. Esse inventário não
é um SBOM de APK, nem certifica todas as bibliotecas de ferramentas externas.

ZMake é GPL-3.0-or-later, copyright2022 MelianMiko; referência externa fixada:
https://github.com/melianmiko/zmake/tree/47acece62528a86a1445d92414cd9a087037eb1c

Seu texto GPL é conservado em third_party/licenses/zmake-47acece/GPL-3.0.txt.
Não está vendorizado nem é módulo obrigatório Android. Usar ferramenta GPL não
é, por si só, afirmação de licença de toda saída; recursos/templates injetados
precisam manter sua proveniência. build.py usa perfil explícito de laboratório,
sem declarar pacote instalável; recibo histórico não é evidência de instalação.

Dependências Python opcionais do builder: Pillow12.3.0 (MIT-CMU e avisos
embarcados), qrcode7.4.2 (BSD), pypng0.20220715.0 (MIT), typing_extensions4.16.0
(PSF-2.0); colorama0.4.6 está no lockfile para plataformas pertinentes. São
ferramentas externas, não vendorizadas. uv.lock conserva versões/hashes.
Gradle/AGP/SDK/NDK/JDK/Rust também não são redistribuídos aqui; seus termos e
avisos são independentes. Consultar termos oficiais antes de provisioná-los.

## Evidência e privacidade

A exportação contém apenas fontes/configuração/docs e fixtures técnicas
sanitizadas ou sintéticas. Sem histórico Git, captura do usuário, ID Library,
endereço Bluetooth unitário, serial, chave, APK ou banco com dados pessoais.
Uma captura relatada da 0.3 mostrou bateria e revisões no próprio app; o resumo
não identifica a unidade nem comprova autenticação Huami/saúde/MCP remoto.
A fixture user_gatt sanitizada continua explicitamente leitura externa por nRF;
não foi renomeada como callback físico do app. Os demais ensaios descritos como
sintéticos não passaram a ser físicos por causa da exportação.

SOURCE-MANIFEST.json registra hashes de todos os arquivos distribuídos, exceto
seu próprio hash. Mudanças desta exportação: licença/metadados, documentação
pública, receitas relativas, recibos sanitizados e construção de endereço
estritamente sintético no teste de sanitização em vez de literal no arquivo.
Nenhuma implementação foi retirada para disfarçar origem ou alterar licença.

## Radar0.4: origem própria e APIs de plataforma

RadarSession.java e RadarGatt.java são implementação local de observações RSSI,
com documentação normativa Android readRemoteRssi/onReadRemoteRssi; não copiam
protocolo Huami ou dados pessoais. Sem GPS, posições inferidas ou modelo de
distância. Testes de rádio são sintéticos no host até teste físico explícito.

## Incremento 0.5

RadarFeedback, RadarVisualModel, RadarPulseView e modo contínuo são código local
sobre as mesmas APIs Android. Sem biblioteca nova, GPS ou modelo espacial.
O usuário relatou que o snapshot0.4 abriu sem erro e bateria74% coincidiu com
a tela; não houve captura/telemetria ou comparação individual dos demais campos.

## Preparação Huami AUTH0.6 (local)

src/huami_auth.rs valida envelopes de InitOperation2021/Huami2021Service no
mesmo commit Gadgetbridge fixado, AGPL-3.0-or-later; crédito2021–2024 Andreas
Shimokawa e José Rebelo. Não inclui cifra, ECDH ou material de chave upstream.

A sonda criptográfica privada preserva o header GPL-3.0-or-later de ECDH_B163
(upstream Andreas Shimokawa2022–2024). Não foi incorporada ao app; ver
docs/crypto-0.6.md para licença e resultado negativo de ordem baixa.

Atualização local BC1.86: dependência oficial/MIT, hash e licença preservados em
third_party/bcprov-1.86-download.json e third_party/licenses/bcprov-1.86/LICENSE.md.
FixtureB163 e seu avisoApache2.0 preservados; ver crypto-tests/README.md. A
observação negativa da referênciaGPL não afirma vulnerabilidade geral no upstream.

## Encrypted2021 transport preparation

`HuamiEncryptedFrames` follows Gadgetbridge's extended2021 protocol at revision
`a0948ee1cbc2a870f91d313f8e37df5f524465f7`; it remains AGPL-3.0-or-later.
Test-only unmodified encoder/decoder/handler copies and attributed AES/CRC subsets
are under `android-bridge/crypto-reference`, with original copyright/license
notices. Compatibility shims are explicitly synthetic, not Bluetooth implementations.
They are excluded from Android source sets. See that directory's README and
`docs/encrypted-transport-0.6.md` for evidence limits and differences in RX checks.
## Offline Zepp capability preparation

`src/zepp_capabilities.rs`, its synthetic tests and example use protocol mappings
from Gadgetbridge a0948ee1cbc2a870f91d313f8e37df5f524465f7 (AGPL-3.0-or-later),
especially Huami2021Service, ZeppOsSupport, HuamiBatteryInfo and
ZeppOsNotificationService. Credit the Gadgetbridge contributors, including
Andreas Shimokawa and José Rebelo; upstream notices remain preserved. The new
module is AGPL-3.0-or-later. Sources and deviations are documented in
docs/zepp-capabilities-0.6.md; fixtures are synthetic, not device captures.


A consulta explícita ReadOnlyBatteryQuery e o ABI zepp_bridge compõem o framing,
crypto e decoders já atribuídos; consulta de serviços baseada em ZeppOsServicesService
(José Rebelo2023–2024) no mesmo commit upstream. Não invoca initializeServices
nem reproduz suas escritas de hora/perfil/configuração. Veja docs/battery-query-0.6.md.

A UI de piloto, parser LocalCredential, contrato PilotConnection e drawable ic_band
são incrementos deste projeto sob AGPL-3.0-or-later. A fixture de UI usa somente
os vetores públicos já atribuídos; nenhuma chave real ou imagem do usuário.
