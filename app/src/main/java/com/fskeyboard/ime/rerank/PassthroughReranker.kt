package com.fskeyboard.ime.rerank

import com.fskeyboard.ime.candidate.Candidate

internal class PassthroughReranker : CandidateReranker {
    override fun rerank(request: RerankRequest): List<Candidate> = request.candidates
}
