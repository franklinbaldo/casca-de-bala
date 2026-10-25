# ABI C local

Header [band7.h](band7.h). Código integrado sob AGPL-3.0-or-later; referências em
[PROVENANCE](../PROVENANCE.md). Buffers pertencem ao chamador, sem referências
JVM retidas nem memória Rust a liberar do lado C.

b7_call abre/ingere/consulta/fecha Activity8 em SQLite por handles opacos.
Entrada limitada, chamadas serializadas, erro explícito e commit atômico antes
de sucesso. Não é sessão BLE, autenticação, coleta física ou cancelamento nativo.
b7_gatt_json é fronteira stateless de plano/decoder/snapshot básico; não faz IO.
Inspecionar envelope de erro além do retorno C; contratos completos nos headers.

Reprodução, na raiz, com Rust e compilador C já instalados:

```sh
cargo build --offline
cc -std=c11 -Wall -Wextra -Werror ffi/host_smoke.c -L target/debug -lband7_terminal -Wl,-rpath,"$PWD/target/debug" -o /tmp/band7-ffi-smoke
/tmp/band7-ffi-smoke
```

Use diretório temporário privado para fixtures de banco; sem dados reais no
repositório. O harness Java/JNI/SQLite também exercita esta ABI e reabertura.
[Build Android e limites host/ART](../BUILDING.md).
