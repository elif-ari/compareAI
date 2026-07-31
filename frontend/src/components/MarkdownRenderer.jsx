import React, { useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Check, Copy } from "lucide-react";

const CodeBlock = ({ children, className }) => {
  const [copied, setCopied] = useState(false);
  const language = className ? className.replace(/language-/, "") : "";
  const codeString = String(children).replace(/\n$/, "");

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(codeString);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error("Kopyalama başarısız:", err);
    }
  };

  return (
    <div className="code-block-container">
      <div className="code-block-header">
        <span className="code-lang">{language || "code"}</span>
        <button type="button" className="code-copy-btn" onClick={handleCopy}>
          {copied ? (
            <>
              <Check size={12} /> Kopyalandı!
            </>
          ) : (
            <>
              <Copy size={12} /> Kopyala
            </>
          )}
        </button>
      </div>
      <pre className="code-block-pre">
        <code>{codeString}</code>
      </pre>
    </div>
  );
};

const MarkdownRenderer = ({ content }) => {
  if (!content) return null;

  return (
    <div className="markdown-content">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          code({ inline, className, children, ...props }) {
            const match = /language-(\w+)/.exec(className || "");
            const isInline = inline || (!match && !String(children).includes("\n"));
            return isInline ? (
              <code className="inline-code" {...props}>
                {children}
              </code>
            ) : (
              <CodeBlock className={className}>{children}</CodeBlock>
            );
          },
          a({ href, children }) {
            return (
              <a href={href} target="_blank" rel="noopener noreferrer">
                {children}
              </a>
            );
          },
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
};

export default MarkdownRenderer;
