# Proveniência e licenças

Este projeto completo é distribuído sob **AGPL-3.0-or-later** (LICENSE).
A escolha não converte bibliotecas externas permissivas ou ferramentas GPL em
código autoral do projeto. Seus avisos/condições permanecem aplicáveis.
Não há afirmação de clean-room ou de autoria própria sobre derivações externas.

## Código com origem/referência externa declarada

Referência Gadgetbridge:
https://github.com/Freeyourgadget/Gadgetbridge/tree/a0948ee1cbc2a870f91d313f8e37df5f524465f7

- crates/casca-core/src/huami_chunk.rs: Huami2021ChunkedEncoder/Decoder; copyright2022–2024
  José Rebelo. Implementação Rust e validações locais preservam AGPL.
- crates/casca-core/src/ingestion.rs: FetchActivityOperation e seus contribuidores; interpretação
  Activity8, ingestão e limites locais. AGPL-3.0-or-later.
- crates/casca-core/src/health_samples.rs: FetchHeartRateRestingOperation,
  FetchHeartRateMaxOperation e FetchSpo2NormalOperation; contribuidores do
  Gadgetbridge. AGPL-3.0-or-later, sem alegação de leitura física universal.
- crates/casca-store/src/activity_db.rs, event_db.rs, sync_session.rs, crates/casca-ffi/src/lib.rs e
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

third_party/rust-licenses.json lista os crates externos compilados para o APK
(aarch64-linux-android, dependências normais e de build de casca-ffi), com as
licenças declaradas e cópias dos avisos. É gerado por scripts/rust-licenses.py, e
o CI falha se ficar desatualizado. Três crates (jni, jni-macros, jni-sys-macros)
não incluem licença no pacote publicado; os textos revisados ficam em
third_party/license-overrides/. SQLite embarcado por libsqlite3-sys conserva seu
aviso de domínio público em third_party/SQLITE-NOTICE.txt.

O APK também inclui a parte Java da btleplug 0.13.5 (droidplug e o jni-utils
vendorizado no repositório dela), copiada do crate travado no Cargo.lock durante
o build e distribuída sob o LICENSE.md da btleplug (BSD-3-Clause, com partes
MIT/Apache-2.0), presente em third_party/licenses/btleplug-0.13.5/.

O fluxo de chave da pulseira (crates/casca-ble/src/account.rs: sessão do login
web do Zepp, lista de dispositivos com os parâmetros do app Zepp e leitura de
auth_key) segue a sequência do huami-token
(https://codeberg.org/argrento/huami-token, MIT, Copyright (c) 2020-2025 Kirill
Snezhko). Nenhum código Python foi copiado; o aviso MIT do projeto original está
em third_party/notices/huami-token-MIT.txt.

O builder ZMake (GPL-3.0-or-later, MelianMiko) e suas dependências Python
opcionais foram removidos desta árvore; nenhum código, recurso ou template
ZMake é distribuído. O inspetor miniapp/packaging/package.py usa somente a
biblioteca padrão do Python.
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

O tarball de fonte de cada release do CI acompanha SHA256SUMS; o
scripts/check-source.mjs barra artefatos e identificadores privados nos arquivos versionados. Mudanças desta exportação: licença/metadados, documentação
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
