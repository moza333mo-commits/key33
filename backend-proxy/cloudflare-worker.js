/**
 * LinguaKey Optional Serverless Backend Proxy (Cloudflare Worker)
 *
 * الغرض من هذا الملف:
 * إذا أردت توزيع تطبيق LinguaKey للمستخدمين بحيث لا يظهر مفتاح Gemini API نهائياً داخل تطبيق الأندرويد،
 * يمكنك رفع هذا الملف مجاناً على Cloudflare Workers (في دقيقتين)، ووضع مفتاحك في Environment Secret باسم GEMINI_API_KEY،
 * ثم وضع رابط الـ Worker داخل إعدادات LinguaKey -> رابط الخادم الوسيط (Backend Proxy URL).
 */

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, {
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "POST, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type, Authorization",
        },
      });
    }

    if (request.method !== "POST") {
      return new Response(JSON.stringify({ error: "Method not allowed" }), {
        status: 405,
        headers: { "Content-Type": "application/json" },
      });
    }

    const apiKey = env.GEMINI_API_KEY;
    if (!apiKey) {
      return new Response(JSON.stringify({ error: "Server GEMINI_API_KEY not configured" }), {
        status: 500,
        headers: { "Content-Type": "application/json" },
      });
    }

    try {
      const body = await request.text();
      const model = "gemini-2.5-flash";
      const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`;

      const response = await fetch(geminiUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: body,
      });

      const data = await response.text();
      return new Response(data, {
        status: response.status,
        headers: {
          "Content-Type": "application/json",
          "Access-Control-Allow-Origin": "*",
        },
      });
    } catch (err) {
      return new Response(JSON.stringify({ error: err.message }), {
        status: 500,
        headers: { "Content-Type": "application/json" },
      });
    }
  },
};
