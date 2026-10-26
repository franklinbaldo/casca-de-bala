# SQLite local para Activity8

Implementação em crates/casca-store/src/activity_db.rs; testes de arquivo real em crates/casca-store/tests/activity_db.rs.
Caminho fechado nesta etapa: bytes sintéticos → decode_activity8 → transação SQLite
→ fechar handle → reabrir → consulta e retomada. É teste host, sem Android ou Band.
HR6/SpO2 usam outro arquivo SQLite experimental em [event-storage.md](event-storage.md); não são persistidos nesta tabela Activity8.

## Escopo e esquema

ActivityDb recebe arquivo local, Source e janela [start,end) imutáveis. Validação
reusa Window: até 4096 minutos por janela e fase de minuto consistente. Cada
registro guarda device_ref, firmware, flag synthetic, timestamp Unix segundos,
offset opcional e os oito bytes Activity8 integralmente em BLOB de tamanho fixo.
Chave primária por origem/firmware/synthetic/timestamp; categoria é Activity8 pela
tabela/versionamento, sem misturar repouso/máxima HR6 ou SpO2. Unknown1 e códigos
0/255 são preservados, não convertidos em ausência nem em valores clínicos.

Application_id B7A8 e user_version 1 identificam o esquema. DB desconhecido ou
versão futura é recusado; não há tentativa automática de migrar/apagar. Esquema
inicial criado transacionalmente. Abertura não remove arquivo existente. Empty
path, :memory: e URI file: são recusados para evitar armazenamento temporário
acidental. Localização privada e permissões do arquivo são responsabilidade do
host Android; não há seleção de caminho por MCP.

## Atomicidade e checkpoint

Ingest começa pelo decoder e valida escopo. BEGIN IMMEDIATE serializa escritores;
dados existentes são relidos dentro da transação. Duplicata exata é idempotente,
conflito de qualquer byte/offset não sobrescreve e cancela lote. Inserts e upsert
do checkpoint ocorrem na mesma transação; resultado Committed só é devolvido após
COMMIT bem-sucedido. Não há cursor em memória atualizado antecipadamente.
O checkpoint fica isolado por origem/firmware/synthetic e limites da janela.

Cursor é o primeiro minuto ausente na janela, nunca o timestamp máximo. Resposta
vazia não salta buracos. Dados fora da janela são rejeitados e dados atrasados
podem preencher gaps. Nenhuma linha zero é criada para preencher ausência.
Janelas diferentes podem compartilhar registros; seu checkpoint pode ficar
conservadoramente atrasado até a próxima ingestão nessa janela. Isso causa replay
idempotente, não salto de dados. End aponta o fim apenas daquela janela explícita,
não declara histórico inteiro coletado nem sensor continuamente medido.

SQLite usa rollback journal DELETE e synchronous FULL. Busy timeout limitado a
100 ms; lock/erro SQL é reportado sem strings do DB que possam carregar dados.
Falha de COMMIT não produz sucesso: reabrir/consultar antes de reconciliar ou
repetir, pois falha de IO real pode exigir diagnóstico do arquivo. Não há loop
automático de retry. Garantias fsync dependem do filesystem/storage; testes de
reabertura e rollback não são ensaio de corte de energia, corrupção ou disco cheio.

## Dependências, reprodução e testes

rusqlite 0.41.0 com feature bundled; Cargo.lock fixa dependências e SQLite vem do
libsqlite3-sys resolvido. O compilador C existente foi usado; não houve instalação
de ferramenta/pacote do sistema. O fetch Cargo prepara dependências de projeto;
com cache pronto o build/teste roda offline. Android exigirá toolchain/target C
adequado e validação separada; não estamos declarando compilação Android.

```sh
# Execute na raiz do projeto
cargo fetch --locked
cargo test --offline --test activity_db
cargo test --offline
cargo fmt --all -- --check
cargo clippy --offline --all-targets -- -D warnings
```

Cinco testes com SQLite em arquivos temporários sintéticos: abrir/reabrir e
comparar todos os campos/proveniência; overlap idempotente, gaps e cursor;
rollback em conflito/entrada inválida; trigger SQL que aborta o checkpoint depois
dos inserts; isolamento de dispositivo/firmware/synthetic e lock de escritor;
recusa de versão desconhecida. Nenhum mock de banco. Fixtures e comandos ficam
no Git, arquivos DB não: são descartados ao fim dos testes. Casos de fault injection
não simulam hardware ou falha elétrica.

Fontes oficiais: [rusqlite Connection](https://docs.rs/rusqlite/0.41.0/rusqlite/struct.Connection.html)
e [SQLite synchronous](https://www.sqlite.org/pragma.html#pragma_synchronous).
Atribuições/AGPL dos registros Activity8 continuam preservadas no módulo.

## Gates de segurança e cobertura

Armazenamento local sem rede, logs de valores ou exportação MCP. SQLite desta
entrega não cifra o arquivo: só fixtures sintéticas são autorizadas aqui. Antes
de dados pessoais: decidir armazenamento privado Android, proteção em repouso,
retenção/quota/exclusão, backup e permissões. Histórico não é sensor bruto, e
persistir Activity8 não constitui coleta completa. Sem coleta física, pareamento,
credenciais, transporte BLE ou autenticação novos; installable:false preservado.
Outras categorias, fetch real e aplicativo Android continuam gates separados.
