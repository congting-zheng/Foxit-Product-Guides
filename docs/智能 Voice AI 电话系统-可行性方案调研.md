# 技术调研可行性方案：智能 Voice AI 电话系统

## 1. 项目背景与需求

* **目标**：构建一款智能 Voice AI 产品，实现用户手机拨入，自动接听、理解客户意图，基于 Topic 场景进行回复，并提供人工切换机制。
* **关键功能**：

  * 手机拨入接入（PSTN / VoIP）
  * 实时语音识别（ASR）与意图理解（NLU）
  * Topic 场景驱动的对话管理
  * 文本转语音（TTS）回复
  * CRM（Salesforce）集成，实现来电弹屏与通话记录
  * AI 无法处理时自动或手动转人工
  * 后台分析、可视化与质量管理
  * 可选：生成式 AI 支持（Amazon Q / LLM）

---

## 2. 可行性技术方案概览

系统整体流程：

```
用户拨入（手机/PSTN） 
      ↓
Amazon Connect 媒体层接入
      ↓
ASR / NLU（Amazon Lex）
      ↓
Topic 场景判断（Contact Flow）
      ↓
TTS 输出（Amazon Polly）
      ↓
AI 无法处理 → 转人工（自动/手动）
      ↓
CRM 集成（Salesforce）
      ↓
分析与监控（Contact Lens、Dashboard）
```

> 系统支持多渠道扩展（Chat、SMS、WhatsApp 等）与上下文保留。

---

## 3. 技术环节与 AWS 服务对照表

| 技术环节                      | 功能描述                                | AWS 服务支持                                                                |
| ------------------------- | ----------------------------------- | ----------------------------------------------------------------------- |
| 电话接入与媒体处理                 | 手机拨入、PSTN/VoIP 接入、IVR 路由、音频处理       | Amazon Connect 托管媒体层，支持 SIP/MEDIA、全球冗余                                  |
| 流程编排（IVR / Contact Flows） | 拖拽式对话流程，支持 API、Lambda、Lex、Polly     | Connect Contact Flows + Lambda + API 调用                                 |
| ASR + NLU                 | 实时语音转文本及意图理解                        | Amazon Lex 提供 ASR 和 NLU，无需额外配置                                          |
| TTS（文本转语音）                | AI 回复转换为自然语音                        | Amazon Polly Neural TTS，支持 SSML、多语言                                     |
| Topic 场景对话管理              | 场景化对话分支和逻辑控制                        | Contact Flows + Lex bots，可自定义业务逻辑                                       |
| 生成式 AI 支持（可选）             | 增强意图理解、对话质量、会话摘要                    | Amazon Q / Amazon Bedrock 集成                                            |
| CRM 集成（Salesforce）        | 来电弹屏、通话记录、上下文接入                     | Amazon Connect CTI Adapter 集成 Salesforce Service/Sales Cloud            |
| 人工切换机制                    | AI 无法处理或用户请求时转人工                    | Contact Flow 设置 Transfer to Queue/Agent，Lex 意图触发，Amazon Q ESCALATION 支持 |
| 分析与质量管理                   | 通话情绪、关键词分析、摘要、仪表盘                   | Contact Lens + Connect Dashboard                                        |
| 多渠道支持                     | 电话、Chat、SMS、WhatsApp、Apple Messages | Amazon Connect Omnichannel 支持                                           |
| 安全与身份管理                   | 认证、权限、SSO                           | Salesforce SSO + SAML 2.0                                               |

---

## 4. 人工切换机制

* **自动转人工**：AI 无法识别意图或识别失败时自动转接到人工队列。
* **用户主动转人工**：用户说“我要人工”，Lex 捕获意图，Flow 执行转接。
* **上下文保留**：转人工时保留已识别意图、槽位信息和部分对话历史。
* **API 控制转人工**：可使用 `TransferContactToAgent` API 进行程序化控制。

---

## 5. 可行性结论

* **Amazon Connect 可覆盖所有核心功能**：

  * 电话接入、ASR/NLU、TTS、Topic 场景管理、CRM 集成、人工切换、分析监控。
* **可选增强**：

  * 生成式 AI（Amazon Q / Bedrock）用于复杂场景理解与智能推荐。
* **优势**：

  * 全托管服务，高可用、可扩展
  * 跨渠道整合能力
  * 内建 AI 与生成式 AI 支持
  * Salesforce CRM 无缝集成，提升坐席效率
* **注意事项**：

  * 高复杂度流程可能需 Lambda 自定义逻辑开发
  * 生成式 AI 功能需确认在目标区域的可用性
  * 多模块串联需关注延迟优化

---

## 6. 建议

1. 优先使用 Amazon Connect + Lex + Polly 构建 MVP 版本。
2. 集成 Salesforce CTI Adapter，实现来电弹屏与通话记录。
3. 配置人工切换机制，保证高复杂度场景的用户体验。
4. 可选引入 Amazon Q 或 Bedrock，提升复杂场景 AI 应答能力。
5. 使用 Contact Lens + Dashboard 做通话分析与质量监控。
6. 测试多语言、并发性能与延迟，确保系统稳定性。

---
