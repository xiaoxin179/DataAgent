/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.workflow.node;

import com.alibaba.cloud.ai.dataagent.constant.DocumentMetadataConstant;
import com.alibaba.cloud.ai.dataagent.dto.prompt.EvidenceQueryRewriteDTO;
import com.alibaba.cloud.ai.dataagent.entity.AgentKnowledge;
import com.alibaba.cloud.ai.dataagent.enums.KnowledgeType;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.mapper.AgentKnowledgeMapper;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.AgentVectorStoreService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import com.alibaba.cloud.ai.dataagent.util.MarkdownParserUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;

/**
 * 证据召回节点。
 *
 * 它承担的任务不是直接召回数据库 Schema，而是先围绕“业务语义证据”做补充检索。
 * 这里的证据主要来自两类知识：
 * - 业务术语知识：例如 PV、GMV、核心用户等业务定义。
 * - 智能体知识库：例如 FAQ、QA、文档片段等。
 *
 * 设计动机：
 * - 用户自然语言里常常有大量隐式业务语义，仅靠数据库表结构无法完整理解。
 * - 先把业务语义补齐，后续 QueryEnhance、SchemaRecall、SQL 生成才更有上下文。
 */
@Slf4j
@Component
@AllArgsConstructor
public class EvidenceRecallNode implements NodeAction {

	private final LlmService llmService;

	private final AgentVectorStoreService vectorStoreService;

	private final JsonParseUtil jsonParseUtil;

	private final AgentKnowledgeMapper agentKnowledgeMapper;

	/**
	 * 执行证据召回。
	 *
	 * 处理流程分两段：
	 * 1. 先让模型把当前问题重写成更适合检索知识库的 standalone query。
	 * 2. 再用这个 query 去向量库召回业务术语和智能体知识，并格式化成统一证据文本。
	 *
	 * 返回值采用“双流拼接”的方式：
	 * - 第一段流展示查询重写过程。
	 * - 第二段流展示证据召回结果摘要。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 用户原始问题是查询改写的主体。
		String question = StateUtil.getStringValue(state, INPUT_KEY);

		// agentId 用于限定向量检索范围，防止不同智能体知识串用。
		String agentId = StateUtil.getStringValue(state, AGENT_ID);
		Assert.hasText(agentId, "Agent ID cannot be empty.");

		log.info("Rewriting query before getting evidence in question: {}", question);
		log.debug("Agent ID: {}", agentId);

		// 多轮历史帮助模型消解“它、上个月、刚才那个表”等上下文指代。
		String multiTurn = StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "(无)");

		// 注意这里不把问题扩写成多个子问题，而是只生成一个更适合知识检索的 standalone query。
		// 原因是业务知识往往有强上下文，盲目拆问题反而容易引入噪声。
		String prompt = PromptHelper.buildEvidenceQueryRewritePrompt(multiTurn, question);
		log.debug("Built evidence-query-rewrite prompt as follows \n {} \n", prompt);

		// 第一段模型流生成 standalone query。
		Flux<ChatResponse> responseFlux = llmService.callUser(prompt);

		// 第二段证据展示由同步检索回调主动推送，因此使用独立 sink 桥接。
		Sinks.Many<String> evidenceDisplaySink = Sinks.many().multicast().onBackpressureBuffer();

		// 两段流共享同一个最终结果 Map。
		final Map<String, Object> resultMap = new HashMap<>();
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(this.getClass(), state,
				responseFlux,
				Flux.just(ChatResponseUtil.createResponse("正在查询重写，以更好召回 Evidence..."),
						ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())),
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()),
						ChatResponseUtil.createResponse("\n查询重写完成。")),
				result -> {
					resultMap.putAll(getEvidences(result, agentId, evidenceDisplaySink));
					return resultMap;
				});

		// 第二段流只负责把证据摘要往前端输出；真正的业务结果仍然复用第一段已构造好的 resultMap。
		Flux<GraphResponse<StreamingOutput>> evidenceFlux = FluxUtil.createStreamingGenerator(this.getClass(), state,
				evidenceDisplaySink.asFlux().map(ChatResponseUtil::createPureResponse), Flux.empty(), Flux.empty(),
				result -> resultMap);
		return Map.of(EVIDENCE, generator.concatWith(evidenceFlux));
	}

	/**
	 * 解析改写结果、执行两类向量召回并构造最终证据。
	 */
	private Map<String, Object> getEvidences(String llmOutput, String agentId, Sinks.Many<String> sink) {
		try {
			// 将模型 JSON 解析成 DTO，并取得独立检索问题。
			String standaloneQuery = extractStandaloneQuery(llmOutput);

			// 无有效改写结果时使用“无”作为明确证据值。
			if (standaloneQuery == null || standaloneQuery.isEmpty()) {
				log.debug("No standalone query from LLM output");
				sink.tryEmitNext("未能进行查询重写。\n");
				return Map.of(EVIDENCE, "无");
			}

			// 先把改写结果发给前端，便于观察后端实际检索的问题。
			outputRewrittenQuery(standaloneQuery, sink);

			// 分别召回业务术语和智能体知识。
			DocumentRetrievalResult retrievalResult = retrieveDocuments(agentId, standaloneQuery);

			// 两类知识都为空时无需继续格式化。
			if (retrievalResult.allDocuments().isEmpty()) {
				log.debug("No evidence documents found for agent: {} with query: {}", agentId, standaloneQuery);
				sink.tryEmitNext("未找到证据。\n");
				return Map.of(EVIDENCE, "无");
			}

			// 将 Document 列表转换为后续 prompt 可直接消费的证据文本。
			String evidence = buildFormattedEvidenceContent(retrievalResult.businessTermDocuments(),
					retrievalResult.agentKnowledgeDocuments());
			log.info("Evidence content built as follows \n {} \n", evidence);

			// 向前端输出可读的召回摘要。
			outputEvidenceContent(retrievalResult.allDocuments(), sink);
			return Map.of(EVIDENCE, evidence);
		}
		catch (Exception e) {
			// 检索或解析异常通过 sink 传播，同时返回空证据保护状态结构。
			log.error("Error occurred while getting evidences", e);
			sink.tryEmitError(e);
			return Map.of(EVIDENCE, "");
		}
		finally {
			// 无论成功失败都完成展示 sink，否则 concatWith 的第二段流不会结束。
			sink.tryEmitComplete();
		}
	}

	/**
	 * 将查询改写过程分段推送给前端。
	 */
	private void outputRewrittenQuery(String standaloneQuery, Sinks.Many<String> sink) {
		// 标题、正文和阶段提示拆成多个 chunk，使页面可以逐步展示。
		sink.tryEmitNext("重写后查询：\n");
		sink.tryEmitNext(standaloneQuery + "\n");
		log.debug("Using standalone query for evidence recall: {}", standaloneQuery);
		sink.tryEmitNext("正在获取证据...");
	}

	/**
	 * 在当前 agent 的知识范围内分别检索业务术语和通用知识。
	 */
	private DocumentRetrievalResult retrieveDocuments(String agentId, String standaloneQuery) {
		// BUSINESS_TERM 用于解释指标、业务别名和领域术语。
		List<Document> businessTermDocuments = vectorStoreService
			.getDocumentsForAgent(agentId, standaloneQuery, DocumentMetadataConstant.BUSINESS_TERM)
			.stream()
			.toList();

		// AGENT_KNOWLEDGE 用于召回该智能体配置的说明、规则和补充知识。
		List<Document> agentKnowledgeDocuments = vectorStoreService
			.getDocumentsForAgent(agentId, standaloneQuery, DocumentMetadataConstant.AGENT_KNOWLEDGE)
			.stream()
			.toList();

		// 合并列表只用于统一判断和前端展示，格式化时仍保留两类知识的边界。
		List<Document> allDocuments = new ArrayList<>();
		if (!businessTermDocuments.isEmpty()) {
			allDocuments.addAll(businessTermDocuments);
		}
		if (!agentKnowledgeDocuments.isEmpty()) {
			allDocuments.addAll(agentKnowledgeDocuments);
		}

		log.info("Retrieved documents for agent {}: {} business term docs, {} agent knowledge docs, total {} docs",
				agentId, businessTermDocuments.size(), agentKnowledgeDocuments.size(), allDocuments.size());

		// 使用 record 一次返回分类列表和合并列表。
		return new DocumentRetrievalResult(businessTermDocuments, agentKnowledgeDocuments, allDocuments);
	}

	/**
	 * 把两类知识文档格式化成统一证据文本。
	 *
	 * 这里最终返回的字符串不是简单拼接，而是借助 `PromptHelper` 包装成更适合后续节点消费的知识提示片段。
	 */
	private String buildFormattedEvidenceContent(List<Document> businessTermDocuments,
			List<Document> agentKnowledgeDocuments) {
		String businessKnowledgeContent = buildBusinessKnowledgeContent(businessTermDocuments);
		String agentKnowledgeContent = buildAgentKnowledgeContent(agentKnowledgeDocuments);

		String businessPrompt = PromptHelper.buildBusinessKnowledgePrompt(businessKnowledgeContent);
		String agentPrompt = PromptHelper.buildAgentKnowledgePrompt(agentKnowledgeContent);

		log.info("Building evidence content: business knowledge length {}, agent knowledge length {}",
				businessKnowledgeContent.length(), agentKnowledgeContent.length());

		return businessKnowledgeContent.isEmpty() && agentKnowledgeContent.isEmpty() ? "无"
				: businessPrompt + (agentKnowledgeContent.isEmpty() ? "" : "\n\n" + agentPrompt);
	}

	/**
 * `buildBusinessKnowledgeContent`：把输入内容转换成另一种更适合下游消费的结构。
 *
 * 阅读这个方法时，建议同时关注它依赖了什么输入，以及结果最后会被哪一层继续消费。
 */
	private String buildBusinessKnowledgeContent(List<Document> businessTermDocuments) {
		if (businessTermDocuments.isEmpty()) {
			return "";
		}

		StringBuilder result = new StringBuilder();
		for (Document doc : businessTermDocuments) {
			result.append(doc.getText()).append("\n");
		}
		return result.toString();
	}

	/**
	 * 智能体知识根据类型不同使用不同格式。
	 *
	 * - FAQ / QA：格式化成 Q/A 形式，适合模型直接理解。
	 * - DOCUMENT：保留来源标题和文件名，适合给模型做文档型证据补充。
	 */
	private String buildAgentKnowledgeContent(List<Document> agentKnowledgeDocuments) {
		if (agentKnowledgeDocuments.isEmpty()) {
			return "";
		}

		StringBuilder result = new StringBuilder();

		for (int i = 0; i < agentKnowledgeDocuments.size(); i++) {
			Document doc = agentKnowledgeDocuments.get(i);
			Map<String, Object> metadata = doc.getMetadata();
			String knowledgeType = (String) metadata.get(DocumentMetadataConstant.CONCRETE_AGENT_KNOWLEDGE_TYPE);

			if (KnowledgeType.FAQ.getCode().equals(knowledgeType) || KnowledgeType.QA.getCode().equals(knowledgeType)) {
				processFaqOrQaKnowledge(doc, i, result);
			}
			else {
				processDocumentKnowledge(doc, i, result);
			}
		}

		return result.toString();
	}

	/**
	 * FAQ / QA 型知识处理逻辑。
	 *
	 * 这里会额外去数据库查一次 `AgentKnowledge`，因为向量检索结果里往往只有片段内容，
	 * 但最终展示时还需要标题与完整答案作为来源补充。
	 */
	private void processFaqOrQaKnowledge(Document doc, int index, StringBuilder result) {
		Map<String, Object> metadata = doc.getMetadata();
		String content = doc.getText();
		Integer knowledgeId = ((Number) metadata.get(DocumentMetadataConstant.DB_AGENT_KNOWLEDGE_ID)).intValue();
		String knowledgeType = (String) metadata.get(DocumentMetadataConstant.CONCRETE_AGENT_KNOWLEDGE_TYPE);

		log.debug("Processing {} type knowledge with id: {}", knowledgeType, knowledgeId);

		if (knowledgeId != null) {
			try {
				AgentKnowledge knowledge = agentKnowledgeMapper.selectById(knowledgeId);
				if (knowledge != null) {
					String title = knowledge.getTitle();
					result.append(index + 1).append(". [来源: ");
					result.append(title.isEmpty() ? "知识库" : title);
					result.append("] Q: ").append(content).append(" A: ").append(knowledge.getContent()).append("\n");

					log.debug("Successfully processed {} knowledge with title: {}", knowledgeType, title);
				}
				else {
					log.warn("Knowledge not found for id: {}", knowledgeId);
				}
			}
			catch (Exception e) {
				log.error("Error getting knowledge by id: {}", knowledgeId, e);
				result.append(index + 1).append(". [来源: 知识库] ").append(content).append("\n");
			}
		}
		else {
			log.error("No knowledge id found for agent knowledge document: {}", doc.getId());
			result.append(index + 1).append(". [来源: 知识库] ").append(content).append("\n");
		}
	}

	/**
	 * 文档型知识处理逻辑。
	 *
	 * 这里尽量拼出“标题-文件名”的来源信息，让后续模型能感知证据出处。
	 */
	private void processDocumentKnowledge(Document doc, int index, StringBuilder result) {
		Map<String, Object> metadata = doc.getMetadata();
		String content = doc.getText();
		Integer knowledgeId = ((Number) metadata.get(DocumentMetadataConstant.DB_AGENT_KNOWLEDGE_ID)).intValue();
		String knowledgeType = (String) metadata.get(DocumentMetadataConstant.CONCRETE_AGENT_KNOWLEDGE_TYPE);
		String title = "";
		String sourceFilename = "";

		log.debug("Processing {} type knowledge with id: {}", knowledgeType, knowledgeId);

		if (knowledgeId != null) {
			try {
				AgentKnowledge knowledge = agentKnowledgeMapper.selectById(knowledgeId);
				if (knowledge != null) {
					title = knowledge.getTitle();
					sourceFilename = knowledge.getSourceFilename();

					log.debug("Successfully processed {} knowledge with title: {}, source file: {}", knowledgeType,
							title, sourceFilename);
				}
				else {
					log.warn("Knowledge not found for id: {}", knowledgeId);
				}
			}
			catch (Exception e) {
				log.error("Error getting knowledge by id: {}", knowledgeId, e);
			}
		}

		String sourceInfo = title.isEmpty() ? "文档" : title;
		if (!sourceFilename.isEmpty()) {
			sourceInfo += "-" + sourceFilename;
		}

		result.append(index + 1).append(". [来源: ");
		result.append(sourceInfo);
		result.append("] ").append(content).append("\n");
	}

	/**
	 * 往前端输出证据摘要，而不是完整文档。
	 *
	 * 这样可以避免把过长原文直接刷到界面上，同时也能让用户知道系统“确实召回到了哪些证据”。
	 */
	private void outputEvidenceContent(List<Document> allDocuments, Sinks.Many<String> sink) {
		if (allDocuments.isEmpty()) {
			return;
		}

		log.info("Outputting evidence content for {} documents", allDocuments.size());
		sink.tryEmitNext("已找到 " + allDocuments.size() + " 条相关证据文档，如下是文档的部分信息\n");

		for (int i = 0; i < allDocuments.size(); i++) {
			Document doc = allDocuments.get(i);
			String content = doc.getText();

			String summary = content.length() > 100 ? content.substring(0, 100) + "..." : content;
			sink.tryEmitNext(String.format("证据%d: %s\n", i + 1, summary));
		}
	}

	private record DocumentRetrievalResult(List<Document> businessTermDocuments, List<Document> agentKnowledgeDocuments,
			List<Document> allDocuments) {
	}

	/**
	 * 从模型输出里提取重写后的 standalone query。
	 *
	 * 这里先用 `MarkdownParserUtil` 去掉模型可能包裹的 Markdown，再交给 `JsonParseUtil` 反序列化为 DTO。
	 */
	private String extractStandaloneQuery(String llmOutput) {
		EvidenceQueryRewriteDTO evidenceQueryRewriteDTO;
		try {
			String content = MarkdownParserUtil.extractText(llmOutput.trim());
			evidenceQueryRewriteDTO = jsonParseUtil.tryConvertToObject(content, EvidenceQueryRewriteDTO.class);
			log.info("For getting evidence, successfully parsed EvidenceQueryRewriteDTO from LLM response: {}",
					evidenceQueryRewriteDTO);
			return evidenceQueryRewriteDTO.getStandaloneQuery();
		}
		catch (Exception e) {
			log.error("Failed to parse EvidenceQueryRewriteDTO from LLM response", e);
		}
		return null;
	}

}
