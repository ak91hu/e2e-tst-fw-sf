(() => {
  if (window.__sfE2e) return;
  const closed = new WeakMap();
  const closedHosts = new Set();
  const original = Element.prototype.attachShadow;
  const trackedAttach = function(options) {
    const root = original.call(this, options); closed.set(this, root);
    if (options.mode === 'closed') closedHosts.add(this);
    return root;
  };
  Element.prototype.attachShadow = trackedAttach;
  const state = { nodes: new Map(), revision: 0, closedTracked: Element.prototype.attachShadow === trackedAttach };
  Object.defineProperty(state, 'closedTracked', {get: () => Element.prototype.attachShadow === trackedAttach});
  const painted = e => {
    const style = getComputedStyle(e), box = e.getBoundingClientRect();
    return box.width > 0 && box.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
  };
  const visible = e => {
    if(!painted(e)) return false;
    // Accessibility-hidden and inert ancestors do not supply actionable semantic controls, including shadow hosts.
    for(let current=e;current;current=current.parentElement || current.getRootNode().host) {
      if(current.hidden || current.inert || current.getAttribute('aria-hidden')==='true') return false;
    }
    return true;
  };
  function elements(root = document) {
    const out = [], seen = new Set(), roots = new Set();
    function walk(r) {
      if (roots.has(r)) return; roots.add(r);
      for (const e of r.querySelectorAll('*')) {
        if (seen.has(e)) continue; seen.add(e); out.push(e);
        if (out.length > 20000) throw Error('DOM traversal limit');
        const shadow = e.shadowRoot || closed.get(e); if (shadow) walk(shadow);
      }
    }
    walk(root); return out;
  }
  const idref = (e, attr, isVisible = visible) => {
    const root = e.getRootNode();
    return (e.getAttribute(attr) || '').split(/\s+/).filter(Boolean).map(id => {
      const found = root instanceof Document ? root.getElementById(id) : root.querySelector('#' + CSS.escape(id));
      return found && isVisible(found) ? found.textContent.trim() : '';
    }).join(' ').trim();
  };
  const label = (e, isVisible = visible) => e.getAttribute('aria-label') || idref(e, 'aria-labelledby', isVisible) ||
    (e.labels ? [...e.labels].filter(isVisible).map(x => x.textContent.trim()).join(' ') : '');
  const role = e => e.getAttribute('role') || (e.isContentEditable ? 'textbox' : '') ||
    ({ BUTTON:'button', SELECT:'combobox', TEXTAREA:'textbox', A:'link', DIALOG:'dialog', H1:'heading', H2:'heading', H3:'heading', H4:'heading', H5:'heading', H6:'heading' }[e.tagName]) ||
    (e.tagName === 'INPUT' ? ({checkbox:'checkbox',radio:'radio',number:'spinbutton',search:'searchbox',range:'slider'}[e.type] || 'textbox') : '');
  const norm = text => (text || '').replace(/^\*\s*/, '').trim();
  function scoped(container, all) {
    if (!container || container === 'page') return {elements:all,matches:1};
    const selection = roots => ({elements:roots.flatMap(root => elements(root)),matches:roots.length});
    const dialogs = all.filter(e => role(e) === 'dialog' && visible(e));
    if (container === 'edit-dialog') {
      const editable = dialogs.filter(d => elements(d).some(e => ['textbox','searchbox','combobox','spinbutton','checkbox','radio','switch','slider'].includes(role(e)) && !e.disabled && visible(e)));
      return selection(editable);
    }
    if (container === 'dialog') {
      return selection(dialogs);
    }
    if (container.startsWith('row:') || container.startsWith('product-row:')) {
      const name = container.slice(container.indexOf(':')+1);
      const rows = all.filter(e => role(e) === 'row' && visible(e) &&
        elements(e).some(c => visible(c) && norm(c.textContent) === name));
      return selection(rows);
    }
    const split=container.indexOf(':');
    if(split>0 && ['dialog','region','group','form'].includes(container.slice(0,split))) {
      const wantedRole=container.slice(0,split), name=container.slice(split+1);
      const roots=all.filter(e => (role(e)===wantedRole || wantedRole==='form' && e.tagName==='FORM') && visible(e) && norm(label(e) || e.getAttribute('data-region'))===name);
      return selection(roots);
    }
    throw Error('Unsupported observation scope');
  }
  function secretPresent(text, secrets) {
    const collapsed = value => value.replace(/\s+/gu,' '), source=collapsed(String(text));
    return secrets.some(secret => {
      if (!secret.length) return false;
      if (secret.length<6) return source.includes(secret);
      const value=collapsed(secret).toLowerCase(), candidate=source.toLowerCase();
      if(candidate.includes(value)) return true;
      const minimum=Math.min(8,Math.ceil(value.length/2)), start=candidate.lastIndexOf(value.slice(0,minimum));
      return start>=0 && candidate.length-start<value.length && value.startsWith(candidate.slice(start));
    });
  }
  const recordLink = e => {
    if(role(e) !== 'link') return false;
    if(/^[a-zA-Z0-9]{15}(?:[a-zA-Z0-9]{3})?$/.test(e.getAttribute('data-record-id') || e.getAttribute('data-recordid') || '')) return true;
    if(!e.getAttribute('href')) return false;
    try {
      const href=e.getAttribute('href'), path=href.startsWith('/') ? href.split(/[?#]/)[0] : new URL(href,location.href).pathname;
      return /^\/(?:(?:s\/)?(?:detail|record)\/|lightning\/r\/(?:[^/]+\/)?)?[a-zA-Z0-9]{15}(?:[a-zA-Z0-9]{3})?(?:\/|$)/.test(path);
    }
    catch { return false; }
  };
  function fingerprint(e, secrets) {
    const attrs = {tag:e.tagName.toLowerCase()};
    if(recordLink(e)) attrs.recordLink = 'true';
    const add = (key, value) => {
      if (value && value.length <= 250 && !secretPresent(value,secrets)) attrs[key] = value;
    };
    add('type',e.getAttribute('type'));
    add('testId',e.getAttribute('data-testid') || e.getAttribute('data-test-id'));
    add('apiName',e.getAttribute('data-field') || e.getAttribute('data-field-name') || e.getAttribute('name'));
    add('busy',e.getAttribute('aria-busy'));
    // Named ancestors give a stable context across DOM reshuffles and Shadow DOM boundaries.
    let parent=e.parentElement || e.getRootNode().host;
    for(let depth=0;parent && depth<12;depth++) {
      if(role(parent) === 'row' && elements(parent).some(recordLink)) attrs.recordRow = 'true';
      if(['dialog','region','group','row','form'].includes(role(parent)) || parent.tagName === 'FORM') {
        const name=norm(label(parent) || parent.getAttribute('data-region'));
        if(name) {add('group',name);break;}
      }
      parent=parent.parentElement || parent.getRootNode().host;
    }
    return attrs;
  }
  function observe({container, secrets, pii}) {
    const all = elements(), nodes = [], rev = ++state.revision; state.nodes.clear();
    const selected = scoped(container, all);
    let total = 0, truncated = false;
    for (const e of selected.elements) {
      if (!visible(e)) continue;
      const r = role(e) || (!e.children.length ? 'text' : ''), l = norm(label(e) || e.getAttribute('placeholder')), text = (e.innerText || e.textContent || '').trim();
      if (!r && !e.matches('.slds-form-element__label,.slds-form-element__control')) continue;
      const sensitive = e.type === 'password' || [l,text,String(e.value ?? '')].some(value => secretPresent(value,secrets)) || pii.includes(l);
      const name = l || norm(text).slice(0, 500), value = sensitive ? '[REDACTED]' : (e.value ?? '');
      if (!name && !r) continue;
      const id = rev + '-' + (++total); state.nodes.set(id, e);
      if (nodes.length < 5000) nodes.push({ id, target: {role:r || null,name:name || null,label:l || null,container:container || 'page'},
        text:sensitive ? '[REDACTED]' : text.slice(0,1000),value:String(value).slice(0,4096),disabled:!!e.disabled || e.getAttribute('aria-disabled') === 'true',checked:!!e.checked,
        attributes:sensitive ? {} : fingerprint(e,secrets) });
      else truncated = true;
    }
    return {nodes,truncated,scopeMatches:selected.matches};
  }
  function fields() {
    const rows = [];
    for (const e of elements()) {
      if (!e.matches('.slds-form-element.test-id__output-root') || !visible(e)) continue;
      const labels = elements(e).filter(x => x.matches('.slds-form-element__label') && visible(x));
      const controls = elements(e).filter(x => x.matches('.slds-form-element__control') && visible(x));
      if (labels.length !== 1 || controls.length !== 1) throw Error('Field label/control ambiguity');
      const control = controls[0], buttons = elements(control).filter(x => role(x) === 'button');
      let value = control.innerText.trim();
      for (const button of buttons.reverse()) {
        const text = (button.innerText || label(button) || button.title || '').trim();
        if (text && value.endsWith(text)) value = value.slice(0,-text.length).trim();
      }
      rows.push({label:norm(labels[0].textContent), value,
        links:elements(control).filter(x => role(x) === 'link' && visible(x)).map(x => ({name:norm(x.textContent),href:x.getAttribute('href')}))});
    }
    return rows;
  }
  function masks({secrets,pii}) {
    state.nodes.clear(); const ids = []; let index = 0;
    const all = elements();
    for (const e of all) {
      if (!painted(e)) continue;
      // Lightning components may expose object/array values; visible text still needs masking.
      const text = String(e.value ?? '') + ' ' + String(e.innerText || e.textContent || ''), l = norm(label(e,painted));
      if (e.type === 'password' || (e.tagName === 'INPUT' && e.autocomplete === 'one-time-code') ||
          pii.includes(l) || secretPresent(text, secrets) && !elements(e).some(c => painted(c) && secretPresent(String(c.value ?? '') + ' ' + String(c.innerText || c.textContent || ''),secrets))) {
        const id = 'mask-' + (++index); state.nodes.set(id,e); ids.push(id);
      }
    }
    return ids;
  }
  function options(controlId, wanted) {
    const control=state.nodes.get(controlId);if(!control || !control.isConnected) return [];
    const ids=(control.getAttribute('aria-controls') || control.getAttribute('aria-owns') || '').trim().split(/\s+/).filter(Boolean);
    const all=elements(), owners=ids.length ? all.filter(e=>ids.includes(e.id)) : [document];
    return [...new Set(owners.flatMap(owner=>elements(owner)))].filter(e=>role(e)==='option' && visible(e) && (wanted===null || norm(label(e) || e.textContent)===wanted));
  }
  window.__sfE2e = { state, observe, fields, masks, visible, elements, options, trackedClosedCount: () => [...closedHosts].filter(host => host.isConnected).length };
})();
