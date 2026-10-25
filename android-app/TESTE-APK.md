# Instalar e testar o APK0.4

**Instalador:** band7-basic-reader-0.4-lab-arm64.apk. Android12+ ARM64, código4.
**Fonte AGPL separada:** band7-basic-reader-0.4-lab-source.tar.gz — não instala.
O nome do app é Minha pulseira — laboratório. Esta versão acrescenta radar por
força de sinal em paradas e Ver snapshot local; não tem GPS ou MCP remoto.

A chave debug temporária é diferente da0.3. Se Android disser que o pacote não
pode atualizar o app existente, remova SOMENTE Minha pulseira — laboratório e
instale0.4. Pontos do radar não são persistidos. Não remover Zepp Life/Mi Fitness,
não resetar nem desfazer vínculo. O APK0.3 anterior permanece preservado.

## Único teste físico novo prioritário

1. Se nRF Connect estiver conectado à Band, desconecte-o sem Unbond/desparear.
   Abra0.4, toque Radar da pulseira e selecione sua pulseira já vinculada.
2. Quando aparecer Pronto para marcar um ponto, fique parado e toque Parei aqui:
   medir ponto. Aguarde o resultado. Ande para outro lugar, pare e meça o ponto2.
3. Informe se aparecem RSSI/amostras nos dois pontos e a comparação de sinal;
   depois toque Encerrar busca. Se não houver sinal, informe a mensagem exata,
   sem MAC ou chave. Capturas da tela são permitidas.

Mais quente/frio é apenas sinal mais forte/fraco; não garante aproximação.
A lista/barras mostra ordem de medição, não mapa da casa, metros ou GPS.
O teste ainda não foi feito por nós no telefone; leitura física RSSI é NOT_RUN.

Opcionalmente, no leitor básico, finalize a leitura e toque Ver snapshot local:
compare bateria/revisões com a tela. O botão não faz outra leitura nem envio.
Licença e código-fonte identifica o pacote correspondente e mostra AGPL offline.
