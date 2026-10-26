# Piloto de produto 0.6

Estado desta integração: assinatura permanente e CI já configurados e comprovados
na release da base 0.5. A proposta 0.6 usa os mesmos guards e certificado; nenhum
segredo integra esta fonte. O CI calcula versionCode monotônico acima de 1003.
Testes de host/build desta integração não são novas execuções ART ou físicas.
Os detalhes abaixo preservam os ensaios históricos e suas limitações; referências
a decisão de assinatura pendente descrevem aquele momento, já superado pelo CI.


O leitor básico e radar0.5 foram preservados. Depois de selecionar e concluir uma
leitura básica, há uma ação adicional **Piloto da sessão · acesso avançado**.
Ela transfere somente a seleção Bluetooth em memória para uma Activity não
exportada. Sem seleção, o botão de autenticação fica desabilitado. A seleção não
é gravada em Intent/Bundle/disco e se perde ao sair/girar/morrer o processo.
Nenhuma credencial é obtida automaticamente e nenhuma instrução de extração é
fornecida neste recorte.

Fluxo: explicar efeito → entrada local explícita → Conectar/AUTH → resposta de
aceitação → **Consultar bateria**, outra ação explícita → resultado validado no
Rust. Não existe consulta automática depois de AUTH. O piloto é limitado à
consulta de serviços+bateria; não inicializa serviços de hora/perfil nem coleta
saúde. Subscrever RX/ACK e enviar prova de autenticação são operações de protocolo,
não promessas de ausência de toda escrita GATT. Não envia credencial em claro.
A implementação genérica upstream não comprova compatibilidade desta unidade.

Localizar/parar e recursos de notificações aparecem como descrições de estado,
sem ação habilitada. Seus comandos/decoders continuam preparados separadamente.
Nenhum acesso às notificações Android ou comando de vibração foi acrescentado.
A autenticação conserva o limite total15s do piloto atual; timeout exige conexão
nova e nova entrada local, sem guardar credencial para retomar automaticamente.

## Privacidade revisada

| Superfície | Controle implementado | Limite conhecido |
| --- | --- | --- |
| Campo | password, máximo32 caracteres hex, sem conversão para String no parser | teclado/IME vê o que o usuário digita; usar teclado confiável |
| Estado salvo | saveEnabled/freezesText desabilitados, sem Bundle/Intent/preferências para chave; limpar antes de salvar/sair/girar/cancelar | não é garantia contra dump da memória do sistema |
| Autofill/conteúdo | IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS e captura de conteúdo desabilitada no campo | não controla provedores/OS comprometidos |
| IME | sem sugestões/aprendizado personalizado nem UI de extração | flags podem depender do teclado |
| Clipboard/compartilhar | não há leitura/cópia automática; copiar/cortar/compartilhar bloqueados no campo | colagem manual pode usar conteúdo que já estava na área de transferência; app não apaga clipboard alheio |
| Prints/recents | FLAG_SECURE apenas na janela separada do segredo; janela de produto/main sem bloqueio global | Android pode ocultar a janela ou impedir a captura enquanto ela estiver aberta; não prometer redação exata por pixel |
| Memória | parsing em byte[16], finally limpa buffer inclusive em exceção; Editable limpo ao enviar/fechar; crypto owner fecha no fim | JVM/BC/JCA/IME podem criar cópias; não há apagamento absoluto garantido |
| Backup/disco | allowBackup=false já existente; nenhuma persistência de chave, resultado ou seleção nesta Activity | políticas de fabricante/transferência não substituem não gravar o segredo |
| Crash/logs | mensagens fixas sem valores de entrada; nenhum logger, analytics, export ou handler de crash novo; app sem permissão INTERNET | relatórios de OS/memória fora do controle do aplicativo não são garantidos como vazios |

O callback de dismiss limpa somente o campo pertencente àquela janela, sem atingir
uma entrada nova se chegar atrasado. Cancelar/sair incrementa a geração de UI antes
de fechar o transporte, rejeitando observers tardios. Resultado de bateria anterior
não é apresentado como resposta de uma tentativa nova; reconectar começa sem valor.

## Validação e assinatura

`LocalCredentialTest` testa parser com CharSequence que proíbe toString/subSequence,
formato, rejeição antes de callback e wipe em sucesso/erro. A fixture de interface
é não exportada, debug-only, usa somente os vetores públicos existentes e **ignora
como credencial** os bytes digitados de fixture. Peer e transporte são simulados;
BC/JNI/Rust/Activity são reais. Instrumentation case `pilot` cobre o produto,
privacidade, cancelamento, rotação, timeout e HOME/retorno; os casos existentes
`activity`, `battery`, `cancel`, `denied` e `connect-only` são regressões separadas.
Recibos privados registram cada resultado e hash; não converter build em PASS ART.

O ícone de framework foi substituído por drawable vetorial próprio (`ic_band`),
resolvendo a falha do dumper aapt sem novas dependências. O APK desta rodada é
**testOnly/x86_64**, assinado apenas com identidade temporária do emulador.
Não é instalador para Franklin nem assinatura de release.0.5 permanece intocada.

Antes de distribuir um piloto ARM64, falta decidir e autorizar identidade de
assinatura distribuível e armazenamento seguro da chave. Não foi gerada assinatura
permanente nem reaproveitada a chave de teste. Uma assinatura diferente pode exigir
remover somente o app laboratório antigo; nunca Zepp Life, vínculo ou dados da Band.
A política de assinatura deve ser resolvida antes de prometer atualização no lugar.

## Roteiro futuro que exige ação/autorização de Franklin

1. Aprovar o piloto físico de conexão/autenticação Huami e uma consulta não mutante
   de serviços+bateria. Não abrange saúde, comandos de vibração ou firmware.
2. Resolver assinatura e APK ARM64/código correspondente. Instalar no celular só
   depois dessa entrega, sem operar remotamente o aparelho.
3. Ter uma credencial legítima já disponível por rota autorizada e digitá-la apenas
   no campo local. **Não enviar no chat**. Se não a tiver, parar e definir outra
   etapa autorizada; este roteiro não autoriza obtê-la nem extrair de outro app.
4. Desconectar o nRF Connect para liberar a conexão, sem reset/desparear. Selecionar
   a Band já vinculada pelo leitor, conferir identificação básica e abrir o piloto.
5. Tocar Conectar, observar aceitação/erro e só então Consultar bateria. Encerre ao
   primeiro erro; não há retry automático. Relatar apenas estado e percentual,
   opcionalmente print da tela depois de fechar a janela de segredo, sem chave ou
   identificadores pessoais.

Até esse teste separado, autenticação e bateria proprietária na unidade permanecem
NOT_RUN. A leitura GATT básica física anterior e o snapshot0.4 não validam esse caminho.

A disponibilidade de leitura usa também batteryCanRequest, não apenas o status
PEER_ACCEPTED: o ACK pendente precisa terminar antes de habilitar o botão. A
regressão adicionada prova que a fila ocupada mantém a ação indisponível. Uma
tentativa anterior do harness chamava performClick antes dessa condição e não
iniciava consulta; o timeout esperado então não podia ocorrer. Não se trata de
perda demonstrada de callback no hardware.

## Resultado observado no emulador

PASS nos seis casos instalados: pilot, activity, battery, cancel, denied e
connect-only; host/JNI completo, crypto/JNI,74 testes Rust, fmt/clippy, build/lint,
aapt badging, manifesto, assinatura e alinhamento. O caso pilot verificou apenas
valores públicos de fixture. Memória temporária wiped nos testes host não equivale
a apagamento de todas as cópias no Android. Prints físicos/TalkBack/fonte grande
e interoperabilidade real continuam NOT_RUN.

O harness inicialmente omitia a Activity no manifesto de fixture e aninhava
runOnMainSync; ambos corrigidos. A espera de cancelamento foi alinhada ao dismiss
e o botão ganhou limpeza explícita. A espera HOME observava onPause cedo demais:
trace registrou3 criadas/2 fechadas no pause e3/3 após onStop, sem vazamento
reproduzido. Falhas intermediárias estão preservadas, não reclassificadas como PASS.

Limpeza final: o guard programado encerrou o emulador antes de adb uninstall;
o comando retornou device offline. O APK testOnly permanece como último estado
confirmado apenas no AVD dedicado. A baseline foi verificada antes dos testes,
sem comandos dirigidos a ela; a verificação final ficou indisponível. Servidor e
emulador próprios foram encerrados e os diretórios de identidade ADB/assinatura
temporária removidos. Não houve novo boot somente para desinstalar. Isso não
invalida os testes já concluídos, mas a desinstalação virtual permanece pendente.
