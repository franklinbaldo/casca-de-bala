# Aplicativo Android0.4: leitura básica e radar RSSI

Consulte [README](../README.md), [build](../BUILDING.md) e
[teste manual](TESTE-APK.md). Android12+/API31, ABI arm64-v8a.
Código da interface/seleção é Java; o núcleo compartilhado é Rust via JNI.
Gradle inclui as classes de android-bridge. Sem AndroidX, Internet ou localização.

Duas rotas explícitas: vinculados salvos requer CONNECT; busca por anúncios
requer SCAN e CONNECT. Não há createBond/removeBond, escrita ou notificações GATT.
Leitura foreground DIS/PnP/BAS com timeout/cancelamento; sair interrompe operação.
Vínculo salvo não garante presença, identidade comercial ou alcance atual.
ReadViewModel separa campo lido/ausente/negado/erro e não inventa bateria0%.

Snapshot estruturado: MainActivity.snapshotForTerminal, no UI thread, somente
sobre leitura já finalizada; não é componente exportado ou servidor MCP.
A captura da versão0.3 confirmou leitura básica em uma unidade; não valida a API
posterior, transporte remoto, autenticação Huami ou históricos de saúde.

O APK0.4 é oferecido separadamente com esta fonte correspondente.
[Modo radar por paradas](../docs/radar.md) usa RSSI da conexão, sem GPS ou novas
permissões. O botão Ver snapshot local mostra o retorno real do núcleo Rust
sobre a leitura já finalizada. A tela Licença e código-fonte identifica o arquivo
fonte correspondente; licença e avisos das dependências estão nos assets.
Não há APK ou assinatura privada neste pacote de fonte. A receita build-apk.sh existe para
laboratório e gera chave debug temporária somente quando executada explicitamente.
A receita foi executada para0.4; a chave foi removida e o APK assinado verificado.
Nenhum ensaio de rádio, pareamento, saúde ou nova instalação foi feito no export.
