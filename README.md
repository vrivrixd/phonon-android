# Phonon para Android

Motor de voz (TTS) para Android com o **Phonon**, da Gradium: vozes neurais que rodam
**offline**, direto no celular. Depois de instalado, aparece como motor de voz do sistema e
funciona com o **TalkBack** e com qualquer app que use a voz do Android.

- Modelo `phonon-40m-multilingual` e as 102 vozes do catálogo, **embutidos no APK** (nada
  para baixar).
- Idiomas: português (Brasil), inglês (Reino Unido, EUA e vozes de personagem), espanhol (Espanha
  e México), francês e alemão.
- Velocidade e tom do sistema e do TalkBack funcionam (via Sonic), com uma velocidade extra
  opcional.
- Interrupção imediata da fala, silêncio inicial cortado, escolha da voz padrão por idioma.
- Requer Android 7 ou superior em processador ARM de 64 bits. Há duas versões da biblioteca
  nativa no APK: uma otimizada (ARMv8.2, quase todo celular desde 2019) e uma compatível, escolhida
  automaticamente.

## Instalar

1. Abra a página **Releases** deste repositório no celular e baixe o `.apk` mais recente.
2. Abra o arquivo e permita a instalação de fontes desconhecidas, se o Android pedir.
3. Abra o app **Phonon** uma vez: no primeiro uso ele prepara o modelo, o que leva alguns segundos.
4. Em **Configurações > Acessibilidade > Saída de texto para voz** (o caminho varia por fabricante),
   escolha **Phonon** como mecanismo preferido. O app tem um botão que abre essa tela.

## Como é compilado

Tudo acontece no GitHub Actions (`.github/workflows/build.yml`):

1. Baixa o runtime oficial [gradium-ai/xn-ptts](https://github.com/gradium-ai/xn-ptts) num
   commit fixo.
2. Baixa o pacote do modelo e das vozes, que fica como anexo do release `model-pack` deste
   repositório (é grande demais para o Git).
3. Quantiza os pesos para q8_0 (menor e mais rápido no celular) e testa a síntese no Linux. As
   amostras `.wav` ficam disponíveis como artefato `amostras-de-voz`.
4. Compila `libptts_ffi.so` para arm64 com Rust e o Android NDK, nas duas variantes.
5. Monta e assina o APK com Gradle.

Cada push em `main` gera um APK como artefato. Uma tag `v*` (por exemplo `v0.1.0`) publica um
release com o APK.

A assinatura usa os segredos `KEYSTORE_B64`, `KEYSTORE_PASSWORD` e `KEY_ALIAS`. Sem eles, o
APK é assinado com uma chave descartável, e as atualizações deixam de instalar por cima.

## Licenças

- Código deste app: MIT.
- Runtime Phonon (gradium-ai/xn-ptts): MIT ou Apache 2.0.
- **Modelo `gradium/phonon-40m-multilingual`: CC BY-NC 4.0, somente uso não comercial.** Este app
  não pode ser vendido nem usado comercialmente enquanto trouxer esse modelo.
- Vozes `gradium/phonon-voices`: Apache 2.0.
- Sonic (Bill Cox), usado para velocidade e tom: Apache 2.0.

Os textos completos estão em `licenses/`. Phonon é da Gradium e é baseado no Pocket TTS e no
Mimi da Kyutai. Este é um projeto independente, sem relação oficial com a Gradium. A lista de
idiomas das vozes vem do addon Phonon para NVDA, de Andre Louis.
