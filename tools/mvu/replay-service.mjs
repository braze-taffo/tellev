import { createServer } from 'node:http';
import { once } from 'node:events';

/** Loopback-only deterministic OpenAI replay. It never forwards a request. */
export async function startReplayService({responses,port=0}) {
  let next=0;
  const requests=[];
  const server=createServer(async(req,res)=>{
    try {
      if(req.method==='GET' && req.url==='/v1/models') {
        res.writeHead(200,{'Content-Type':'application/json'});
        res.end(JSON.stringify({object:'list',data:[{id:'mvu-replay',object:'model',created:0,owned_by:'local'}]}));return;
      }
      if(req.method!=='POST'||req.url!=='/v1/chat/completions') {res.writeHead(404);res.end();return;}
      const chunks=[];let length=0;
      for await(const chunk of req) {
        length+=chunk.length;if(length>16*1024*1024)throw Error('Replay request exceeds 16 MiB');chunks.push(chunk);
      }
      const raw=Buffer.concat(chunks).toString('utf8'),body=JSON.parse(raw);
      // Retain exact request body for prompt comparisons, never retain auth headers.
      requests.push({ordinal:requests.length,raw,body});
      if(next>=responses.length) {res.writeHead(409,{'Content-Type':'application/json'});res.end(JSON.stringify({error:{message:'Replay fixtures exhausted'}}));return;}
      const content=responses[next++];
      const envelope={id:'replay-completion',created:0,model:'mvu-replay'};
      if(body.stream) {
        res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache'});
        const send=delta=>res.write(`data: ${JSON.stringify({...envelope,object:'chat.completion.chunk',choices:[{index:0,delta,finish_reason:null}]})}\n\n`);
        send({role:'assistant'});
        for(const c of Array.from(content))send({content:c});
        res.write(`data: ${JSON.stringify({...envelope,object:'chat.completion.chunk',choices:[{index:0,delta:{},finish_reason:'stop'}]})}\n\ndata: [DONE]\n\n`);
        res.end();
      } else {
        res.writeHead(200,{'Content-Type':'application/json'});
        res.end(JSON.stringify({...envelope,object:'chat.completion',choices:[{index:0,message:{role:'assistant',content},finish_reason:'stop'}],usage:{prompt_tokens:0,completion_tokens:0,total_tokens:0}}));
      }
    } catch(error) {
      if(!res.headersSent)res.writeHead(400,{'Content-Type':'application/json'});
      res.end(JSON.stringify({error:{message:error.message}}));
    }
  });
  server.listen(port,'127.0.0.1');await once(server,'listening');
  return {url:`http://127.0.0.1:${server.address().port}/v1`,requests,
    close:()=>new Promise((resolve,reject)=>server.close(e=>e?reject(e):resolve()))};
}
