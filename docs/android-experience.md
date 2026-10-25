# Experiência Android básica

Tela Minha pulseira com ações Já vinculadas ao celular e Buscar por perto.
Permissões solicitadas no momento da ação e retomadas uma vez após concessão.
Vinculados usam CONNECT sem depender de SCAN; anúncios usam ambos. Dispositivo
selecionado por pessoa, sem confirmação de modelo pelo nome ou RSSI.

Estados separados para Bluetooth ausente/desligado/em transição, permissão
negada, lista vazia, busca cancelada/timeout e falha. Busca limitada e cancelável;
seleção/sair encerra scanner e invalida callbacks antigos. Lista salva não prova
presença ou alcance, e sinal inicial da lista não é estimador de distância.

Leitura passa por conectando → lendo → concluída/parcial/sem informação ou erro.
Bateria ausente permanece ausente; zero é leitura válida. Campos recebidos
sobrevivem ao encerramento normal da conexão. Identificadores completos não
aparecem na home; capturas são permitidas. Não há configuração de saúde, conexão
permanente, localização, servidor remoto, pareamento ou firmware nesse fluxo.

O snapshot novo oferece API interna ao terminal, não botão remoto já funcional.
Testes host cobrem políticas e modelos com fixtures; a captura relatada da0.3
confirma somente a leitura básica. TalkBack, texto grande, cada recuperação em
Android físico e o snapshot novo permanecem testes manuais separados.

Fontes de plataforma:
- https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices
- https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getBondedDevices()
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE
