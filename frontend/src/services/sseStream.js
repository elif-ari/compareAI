// Backend'in POST + text/event-stream (Server-Sent Events) uçlarını (bkz. ChatController#
// streamMessage / streamAutoDebate) tüketmek için küçük bir yardımcı.
//
// Neden native EventSource DEĞİL: EventSource sadece GET destekler ve custom body/headers
// gönderemez, bizim isteklerimiz (prompt, conversationId, providers, vb.) POST body'de. Bu yüzden
// fetch()'in ReadableStream'ini manuel olarak SSE formatına göre ("event:...\ndata:...\n\n"
// blokları) parse ediyoruz.
//
// onEvent(eventName, data) her "event: X" bloğu tamamlandığında, data JSON.parse edilmiş
// haliyle çağrılır (parse başarısız olursa ham string olarak geçilir).
export async function streamChatRequest(url, payload, { onEvent, signal } = {}) {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
    signal,
  });

  if (!response.ok || !response.body) {
    let detail = "";
    try {
      detail = await response.text();
    } catch {
      // yoksay
    }
    throw new Error(`Stream başlatılamadı (HTTP ${response.status}). ${detail}`.trim());
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const processBlock = (rawBlock) => {
    if (!rawBlock.trim()) return;
    let eventName = "message";
    const dataLines = [];
    for (const line of rawBlock.split("\n")) {
      if (line.startsWith("event:")) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).trim());
      }
    }
    if (dataLines.length === 0) return;
    const rawData = dataLines.join("\n");
    let parsed;
    try {
      parsed = JSON.parse(rawData);
    } catch {
      parsed = rawData;
    }
    onEvent?.(eventName, parsed);
  };

  // eslint-disable-next-line no-constant-condition
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let boundaryIndex;
    while ((boundaryIndex = buffer.indexOf("\n\n")) !== -1) {
      const rawBlock = buffer.slice(0, boundaryIndex);
      buffer = buffer.slice(boundaryIndex + 2);
      processBlock(rawBlock);
    }
  }
  // Akış bitti ama son bloğun sonunda "\n\n" gelmemiş olabilir (nadir) - kalanı da işle.
  processBlock(buffer);
}
