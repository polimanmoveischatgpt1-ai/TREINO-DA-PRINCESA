(()=>{
  const style=document.createElement('style');
  style.textContent=`
    .sets{grid-template-columns:74px 1fr 1fr!important}
    .setcheck{border:1px solid #ffc0d5;background:#fff2f7;color:#c83b72;border-radius:11px;padding:10px 5px;font-weight:800;font-size:12px;min-height:42px}
    .setcheck.on{background:var(--p);border-color:var(--p);color:#fff}
    .auto-rest-box{display:flex;align-items:center;gap:10px;margin:12px 0;padding:11px;border:1px solid var(--line);border-radius:14px;background:#fff8fb}
    .auto-rest-box input{width:auto!important;transform:scale(1.25);accent-color:var(--p)}
    .alert-actions{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:10px}
    @media(max-width:440px){.sets{grid-template-columns:68px 1fr 1fr!important}}
  `;
  document.head.appendChild(style);

  openW=function(id){
    CW=id;
    const w=WORKOUTS.find(x=>x.id===id);if(!w)return;
    const d=migrateDraft(id,w);
    q('#stitle').innerHTML=`<h2 style="margin:16px 0 2px">${escapeHtml(w.n)}</h2><div class="mut">${escapeHtml(w.s)} • ${escapeHtml(w.d)}</div>`;
    q('#elist').innerHTML=w.e.length?w.e.map(e=>{
      const ex=d[e.id]||{};
      return `<div class="ex"><div class="extop"><button class="check ${ex.done?'on':''}" data-excheck="${e.id}" onclick="toggleExercise('${e.id}',this)">${ex.done?'✓':''}</button><div class="grow"><h4>${escapeHtml(e.name)}</h4><div class="mut">${e.sets} séries • ${escapeHtml(e.reps)} reps • descanso ${fmtRest(e.rest)}</div>${Array.from({length:e.sets},(_,j)=>{
        const v=ex[j]||{};
        return `<div class="sets"><button class="setcheck ${v.done?'on':''}" onclick="toggleSet('${e.id}',${j},this)">${v.done?'✓ ':''}Série ${j+1}</button><input inputmode="decimal" placeholder="Carga kg" data-eid="${e.id}" data-j="${j}" data-t="w" value="${attr(v.w||'')}"><input inputmode="numeric" placeholder="Reps" data-eid="${e.id}" data-j="${j}" data-t="r" value="${attr(v.r||'')}"></div>`;
      }).join('')}<div class="exercise-rest"><button class="btn soft" onclick="startExerciseRest('${e.id}')">⏱ Descanso ${fmtRest(e.rest)}</button></div></div></div></div>`;
    }).join(''):'<div class="card empty">Este treino ainda não tem exercícios. Edite o treino e adicione exercícios.</div>';
    qa('#elist input').forEach(x=>x.addEventListener('input',draft));
    go('session');
  };

  window.toggleSet=function(eid,j,b){
    unlockAudio();draft();
    const d=load('draft_'+CW,{});d[eid]??={};d[eid][j]??={};
    d[eid][j].done=!d[eid][j].done;
    const w=WORKOUTS.find(x=>x.id===CW),e=w?.e.find(x=>x.id===eid);
    const all=!!e&&Array.from({length:e.sets},(_,k)=>!!d[eid]?.[k]?.done).every(Boolean);
    d[eid].done=all;save('draft_'+CW,d);
    b.classList.toggle('on',d[eid][j].done);b.textContent=(d[eid][j].done?'✓ ':'')+'Série '+(j+1);
    const exb=document.querySelector('[data-excheck="'+eid+'"]');if(exb){exb.classList.toggle('on',all);exb.textContent=all?'✓':''}
    if(d[eid][j].done&&load('auto_rest_v1',true)!==false&&e)setTimer(e.rest,`Descanso • ${e.name}`,true);
  };

  beep=function(){
    try{
      unlockAudio();if(!audioCtx)return;
      const t=audioCtx.currentTime;
      [880,1040,880].forEach((f,i)=>{
        const o=audioCtx.createOscillator(),g=audioCtx.createGain(),st=t+i*.24;
        o.connect(g);g.connect(audioCtx.destination);o.frequency.value=f;
        g.gain.setValueAtTime(.001,st);g.gain.exponentialRampToValueAtTime(.22,st+.02);g.gain.exponentialRampToValueAtTime(.001,st+.18);
        o.start(st);o.stop(st+.2);
      });
    }catch{}
  };
  timerFinished=function(){
    beep();
    if(navigator.vibrate)try{navigator.vibrate([280,120,280,120,420])}catch{}
    msg('Descanso finalizado! Próxima série 💪');sendTimerNotification();
  };
  window.testRestAlert=function(){
    unlockAudio();beep();if(navigator.vibrate)try{navigator.vibrate([180,90,180])}catch{};msg('Teste do aviso 🔊');
  };
  window.saveAutoRest=function(v){save('auto_rest_v1',!!v);msg(v?'Descanso automático ativado ⏱️':'Descanso automático desativado')};

  function enhanceProfile(){
    const nb=q('#notifBtn');if(!nb||q('#autoRestV21'))return;
    const card=nb.parentElement;
    const notice=card.querySelector('.notice');
    if(notice)notice.textContent='Ao concluir uma série, o descanso começa automaticamente. Quando termina, o app toca um aviso sonoro e também tenta notificar/vibrar conforme o que o iPhone permitir.';
    const box=document.createElement('label');box.className='auto-rest-box';
    box.innerHTML='<input id="autoRestV21" type="checkbox"><span><b>Descanso automático</b><br><span class="mut">Começar o cronômetro ao marcar uma série como concluída.</span></span>';
    const ck=box.querySelector('input');ck.checked=load('auto_rest_v1',true)!==false;ck.addEventListener('change',()=>saveAutoRest(ck.checked));
    card.insertBefore(box,nb);
    nb.classList.remove('full');nb.style.marginTop='0';
    const actions=document.createElement('div');actions.className='alert-actions';
    const test=document.createElement('button');test.className='btn soft';test.textContent='🔊 Testar toque';test.addEventListener('click',testRestAlert);
    nb.parentNode.insertBefore(actions,nb);actions.appendChild(test);actions.appendChild(nb);
    const version=q('.version');if(version)version.textContent='Treino da Princesa • versão 2.1';
  }

  enhanceProfile();
  const oldGo=go;
  go=function(id){oldGo(id);if(id==='profile')enhanceProfile()};
  document.addEventListener('pointerdown',unlockAudio,{once:true});
})();