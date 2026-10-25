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
