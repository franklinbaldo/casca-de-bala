# Identificação GATT básica — evidência externa sanitizada

Evidência fornecida pelo usuário em 25/10/2026 UTC (data local 24/10), por leitura
com **nRF Connect**, não com nosso aplicativo. Somente valores técnicos
sanitizados em fixtures/user-gatt-sanitized.json; sem log original, nome
com sufixo, endereço Bluetooth, serial ou valor de System ID. Isto não autoriza
nova conexão, leitura, notification, pareamento ou escrita por nosso código.

| Evidência fornecida | Resultado observado pelo usuário | Limite |
| --- | --- | --- |
| Modelo comercial / companheiro | Xiaomi Smart Band 7 / Zepp Life | Não identifica região/deviceSource nem prova APK nosso |
| Conexão / discovery | Status 0 / status 0 | Sucesso do diagnóstico nRF, não autenticação Huami |
| Link | Encryption ENABLED, AES 16 LE | Não comprova autenticação da camada de aplicação, identidade/MITM ou canais de histórico |
| 180A / 2A27 | Hardware Revision String: 0.91.130.5 | Versão técnica, não identificador unitário |
| 180A / 2A28 | Software Revision String: 2.2.0.1 | 2A28 não é Firmware Revision String 2A26; não renomear característica nem inferir compatibilidade |
| 180A / 2A50 | 01 57 01 67 00 02 01 | VendorSource 1, vendor 0x0157, product 0x0067 (103), ProductVersion BCD 0x0102 = 1.0.2 |
| 180F / 2A19 | 0x5B = 91% | Snapshot histórico do relato, não bateria atual nem dado de saúde |
| 180D / 2A37 Notify | Somente descoberto | Nenhuma medida HR recebida; não foi habilitada notificação pelo projeto |
| FEE0 | Chars 16/17 NWNR, 01 NW, 02 N, 04 NWNR, 05 N, 06 NWNR | Propriedades preservadas como relatadas, sem adivinhar permissões semânticas; não houve tráfego proprietário capturado |
| 1812 / 2A4A, 2A4B, 2A4D | BLUETOOTH_PRIVILEGED ao tentar ler | Restrição de plataforma, não prova de defeito na Band; sem tentativa de bypass/retry |

Os UUIDs proprietários relatados usam sufixo -0000-3512-2118-0009af100700;
não devem ser reduzidos a UUIDs SIG de 16 bits. Service FEE0 não é autorização de
operação. MiBand7Coordinator observado aceita deviceSources 260/262/263/264/265,
um namespace diferente de DIS Product ID. O núcleo retorna Unknown para a
conversão PnP→deviceSource: **103 não vira 263**. O candidato internacional de
laboratório e seu appId continuam sem validação para esta unidade; installable:false.

## Código offline implementado

crates/casca-core/src/gatt_basics.rs reutiliza o núcleo Rust/Availability; não há outro backend
nem acesso direto a Android. `decode` entende revisões UTF-8, PnP little-endian de
7 bytes e percentual de bateria de 1 byte. Validação conservadora do host: revisão
1–64 bytes sem controles/espaço vazio; vendorSource 1/2; quatro dígitos BCD válidos;
bateria 0..100. Estes limites não são MTU nem declaração de valores universais.
Campo inválido não vira dado ausente, percentual aproximado ou falha do sensor.

`interpret` mantém Reader UserNrfConnect/OurApp e estados NotRead,
PermissionDenied, GattFailure, InvalidValue e Read. Sem callback de leitura não
há Read. Status GATT diferente de zero prevalece sobre bytes eventualmente
presentes; string inválida ou payload vazio com status 0 não vira sucesso.
O enum OurApp é suporte para callbacks futuros, não alegação de que já ocorreram.

`plan` retorna somente decisões offline. Aceita até 128 características e exige
par exato serviço/característica, propriedade Read e permissão CONNECT do
**nosso app**, não a permissão do nRF nem o estado de cifra do link. Lista mínima:
180A:2A27/2A28/2A50 e 180F:2A19. Não escreve, não solicita privilegio, não habilita
notifications nem produz chamadas BluetoothGatt. Descoberta de serviço sozinha
não implica presença/readability de todas as suas características.

HID 1812 e características 2A4A–2A4D são excluídos mesmo que o caller marque Read.
Serial 2A25 e System ID 2A23 são excluídos por privacidade. Todo o restante,
incluindo HR Notify e vendor UUIDs, fica fora deste plano básico. O adaptador
Android futuro só deve construir Bluetooth16 após verificar a base canônica
Bluetooth; Vendor128 não é abreviação intercambiável. Plano não é autorização:
permissões do Android e resultado de cada callback continuam necessários.

## Fontes primárias e reprodução

- [Bluetooth SIG DIS v1.2](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/DIS_v1.2/out/en/index-en.html): revisões, PnP, ordem little-endian, namespace vendor e BCD JJ.M.N. Também distingue dados de produto e identificadores unitários.
- [AOSP GattService.java](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/gatt/GattService.java), blob consultado 09c648f2bee790b72f7a92961ca348ed028e0786: HID_UUIDS/permissionCheck aplicam BLUETOOTH_PRIVILEGED. Não sabemos a revisão AOSP exata do telefone; o mecanismo documentado explica a restrição, não atribui versão ao usuário.
- Gadgetbridge fixado em a0948ee, [MiBand7Coordinator](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/huami/miband7/MiBand7Coordinator.java) e [InitOperation2021](https://github.com/Freeyourgadget/Gadgetbridge/blob/a0948ee1cbc2a870f91d313f8e37df5f524465f7/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/operations/init/InitOperation2021.java): deviceSources e autenticação própria/derivação de chave de sessão além da cifra de link. Nenhuma implementação criptográfica foi acrescentada.

```sh
# Execute na raiz do projeto
cargo test --offline --test gatt_basics
cargo test --offline
cargo fmt --all -- --check
cargo clippy --offline --all-targets -- -D warnings
```

Quatro testes novos validam fixture técnica sanitizada, bytes/limites/erros,
interpretação de provenance e planejamento/recusa por permissão, HID, base UUID
e serviço incorreto. Casos negativos adicionais são sintéticos. Testes anteriores
de saúde continuam exclusivamente sintéticos; este novo fixture externo contém
apenas os valores técnicos aprovados acima, não medições pessoais de saúde.

## Próxima prova e limites

O diagnóstico sanitizado da fixture é externo (nRF), não callback do app.
Posteriormente, uma captura de usuário da0.3 mostrou leitura básica no app.
Isso não valida todos os modelos/firmwares nem autenticação Huami, histórico
ou o snapshot estruturado novo em ART. Build e testes atuais: [validation.md](validation.md).
Antes de saúde/miniapp/firmware, cumprir gates específicos sem inferir transporte
a partir de hmBle ou de presença de serviços.
