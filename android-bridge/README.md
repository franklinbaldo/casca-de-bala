# Java/JNI e núcleo Rust

Licença AGPL-3.0-or-later; todos os avisos C/Rust existentes foram preservados.
BasicReadSession é controlador serial foreground; DiscoverySession cuida da
seleção; ReadViewModel apresenta disponibilidade por campo; BasicSnapshot leva
uma leitura finalizada à fronteira Rust, sem IO adicional. GattBasics.process
reutiliza o contrato JSON interno bounded, não GATT/MCP externo.

ActivitySession/ActivityWorker são caminho distinto para Activity8 já autenticado/
decifrado pelo chamador futuro: Java → JNI → ABI → SQLite. Não expõem a nova
SyncSession Rust ou cancelamento nativo de uma transação em execução. Falhas
não admitem retry cego após efeito possível. Testes usam somente dados sintéticos.

Na raiz independente do projeto:

```sh
sh android-bridge/run_host.sh
sh android-bridge/run_worker_host.sh
```

O primeiro compila classes reais e shim C, liga a cdylib Rust e executa JVM
-Xcheck:jni com SQLite real, lifecycle, limites, erros e snapshots. O segundo
verifica o worker com backend sintético, sem afirmar integração de rádio.
O header subset host é apenas Linux x86_64 e declara base na especificação JNI;
Android usa jni.h oficial NDK. [Build e versões](../BUILDING.md).
[Contrato básico](../docs/gatt-bridge.md) e [snapshot](../docs/basic-snapshot.md).
