---
applyTo: "src/main/java/**/*.java,src/main/resources/assets/**/lang/*.json"
---

# i18n 规则

1. 玩家可见文字使用 `Translator.tr()`，原则上不使用 `Text.literal()`。
2. 所有语言资源同步维护。
3. 消息需写清楚操作者、金融标的、订单、持仓和操作内容等对象。
4. 可使用 MOTD 样式增强提示，但不引入 emoji 或特殊 Unicode。
5. 翻译专名先对比既有文档；不确定时询问。
6. 语言文件参数不能被单引号包裹。
7. 带参数的 `MessageFormat` 文本中，英文单引号写作两个单引号。
