# Miniapp API1: preparação e inspeção offline

`package.py` prepara o bundle canônico de fontes e inspeciona payloads ZIP/BIN
com limites de tamanho, expansão, nomes, manifesto, assets e entrypoints.
Somente biblioteca padrão do Python; nenhum resultado é instalável
(`installable:false`). Testes negativos são sintéticos.

Na raiz deste projeto:

```sh
python3 -m unittest discover -s miniapp/packaging -v
python3 miniapp/packaging/package.py prepare miniapp
```

O builder ZMake de laboratório (Python 3.12, Pillow, qrcode, uv.lock) foi
removido da árvore: não participava do APK nem do CI, e o alvo l66w_2/deviceSource263
e o appId de teste nunca foram validados na unidade. A receita e o recibo do
candidato histórico permanecem no histórico Git, no commit
8c0f9b5502d14153544c29b30b41c81050319e52 (`miniapp/packaging/build.py`).
Reintroduzir o builder somente quando houver alvo identificado e gate de
instalação aprovado no [roteiro](../../docs/roadmap.md).
