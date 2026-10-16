# EDITH (Android)

Assistente de voz para os oculos do Zeylor: conversa em tempo real com o Gemini Live,
fala pelo fone Bluetooth e usa uma camera Wi-Fi (drone E88) quando pedem para olhar algo.

- `EdithService`: servico em primeiro plano (funciona com a tela apagada).
- `CameraWifi`: fala UDP com a camera pela rede Wi-Fi dela, mesmo com o 4G como internet.
- A chave do Gemini e digitada no app e fica so no celular. NUNCA coloque a chave no codigo.
