# TODO

## Pending Implementation

- Add client-side model selection support so users can choose which Hack Club AI model to use, in addition to setting the API key.
- Fetch or maintain the available model list from Hack Club AI and present it in the client UI.
- Send the selected model to the server together with user settings.
- Update AI request flow to use the selected model instead of a fixed model.
- Persist the selected model in client config (`construct.json`) and re-send it on reconnect.
