(function () {
  'use strict';

  var state = {
    graph: null,
    commitsByHash: {},
    selectedCommitHash: null,
    selectedCommitDetail: null,
    selectedFilePath: null,
    network: null,
    nodesDataSet: null,
    edgesDataSet: null,
    levelsByHash: {}
  };

  function initTheme() {
    var root = document.documentElement;
    var key = 'theme';
    var saved = null;
    try {
      saved = localStorage.getItem(key);
    } catch (e) {}
    if (saved === 'light' || saved === 'dark') {
      root.dataset.theme = saved;
    }
    var btn = document.getElementById('theme-btn');
    if (btn) {
      btn.addEventListener('click', function () {
        var isDark = root.dataset.theme
          ? root.dataset.theme === 'dark'
          : window.matchMedia('(prefers-color-scheme: dark)').matches;
        var next = isDark ? 'light' : 'dark';
        root.dataset.theme = next;
        try {
          localStorage.setItem(key, next);
        } catch (e) {}
        refreshNetworkTheme();
      });
    }
  }

  function getThemeColors() {
    var cs = getComputedStyle(document.documentElement);
    function token(name, fallback) {
      var v = cs.getPropertyValue(name);
      return v ? v.trim() : fallback;
    }
    var isDark = document.documentElement.dataset.theme
      ? document.documentElement.dataset.theme === 'dark'
      : window.matchMedia('(prefers-color-scheme: dark)').matches;
    return {
      paper: token('--paper', isDark ? '#0c0d10' : '#fbfbfa'),
      card: token('--card', isDark ? '#14161a' : '#ffffff'),
      sunk: token('--sunk', isDark ? '#101216' : '#f3f4f4'),
      ink: token('--ink', isDark ? '#f4f5f6' : '#0f1112'),
      ink2: token('--ink-2', isDark ? '#c3c8cd' : '#3a3f44'),
      ink3: token('--ink-3', isDark ? '#8b9198' : '#6b7177'),
      line: isDark ? 'rgba(244,245,246,0.22)' : 'rgba(15,17,18,0.18)',
      accent: token('--accent', isDark ? '#9cc9da' : '#4f7a8c'),
      accentSoft: token('--accent-soft', isDark ? '#1b2a31' : '#e3edf1')
    };
  }

  function computeLevels(commits) {
    var byHash = {};
    commits.forEach(function (c) {
      byHash[c.hash] = c;
    });
    var memo = {};
    var visiting = {};
    function rank(hash) {
      if (Object.prototype.hasOwnProperty.call(memo, hash)) {
        return memo[hash];
      }
      if (visiting[hash]) {
        return 0;
      }
      visiting[hash] = true;
      var c = byHash[hash];
      var r = 0;
      if (c && Array.isArray(c.parents) && c.parents.length > 0) {
        var maxParentRank = -1;
        c.parents.forEach(function (p) {
          if (byHash[p]) {
            var pr = rank(p);
            if (pr > maxParentRank) {
              maxParentRank = pr;
            }
          }
        });
        r = maxParentRank + 1;
      }
      visiting[hash] = false;
      memo[hash] = r;
      return r;
    }

    var maxRank = 0;
    commits.forEach(function (c) {
      var r = rank(c.hash);
      if (r > maxRank) {
        maxRank = r;
      }
    });

    var levels = {};
    commits.forEach(function (c) {
      levels[c.hash] = maxRank - memo[c.hash];
    });
    return levels;
  }

  function truncateLine(text, maxLen) {
    if (!text) {
      return '';
    }
    var firstLine = String(text).split(/\r?\n/)[0].trim();
    if (firstLine.length <= maxLen) {
      return firstLine;
    }
    return firstLine.slice(0, maxLen - 1) + '…';
  }

  function formatNodeLabel(commit, headBranch) {
    var lines = [];
    var badges = [];
    var refs = Array.isArray(commit.refs) ? commit.refs : [];
    if (commit.isHead) {
      if (headBranch && refs.indexOf(headBranch) >= 0) {
        badges.push('HEAD -> ' + headBranch);
        refs.forEach(function (r) {
          if (r !== headBranch) {
            badges.push(r);
          }
        });
      } else {
        badges.push('HEAD');
        refs.forEach(function (r) {
          badges.push(r);
        });
      }
    } else if (refs.length > 0) {
      refs.forEach(function (r) {
        badges.push(r);
      });
    }

    if (badges.length > 0) {
      lines.push('[' + badges.join(', ') + ']');
    }
    lines.push(commit.shortHash + ' · ' + truncateLine(commit.message, 20));
    return lines.join('\n');
  }

  function buildNodeStyle(commit, isSelected, colors) {
    var hasRef = commit.isHead || (Array.isArray(commit.refs) && commit.refs.length > 0);
    var bg = hasRef ? colors.accentSoft : colors.card;
    var border = hasRef ? colors.accent : colors.line;
    var borderWidth = hasRef ? 2 : 1.2;
    return {
      color: {
        background: bg,
        border: border,
        highlight: {
          background: colors.accentSoft,
          border: colors.accent
        },
        hover: {
          background: colors.accentSoft,
          border: colors.accent
        }
      },
      borderWidth: borderWidth,
      borderWidthSelected: 2.6,
      font: {
        color: colors.ink,
        face: '"JetBrains Mono", ui-monospace, monospace',
        size: 12,
        align: 'left'
      }
    };
  }

  function buildEdgeStyle(edge, colors) {
    var isMergeSecondParent = edge.idx === 1;
    return {
      color: {
        color: isMergeSecondParent ? colors.ink3 : colors.accent,
        highlight: colors.accent,
        hover: colors.accent
      },
      dashes: isMergeSecondParent ? [5, 5] : false,
      width: isMergeSecondParent ? 1.8 : 2.2
    };
  }

  function renderGraph(graph) {
    var container = document.getElementById('dag-canvas');
    if (!container) {
      return;
    }
    var commits = graph.commits || [];
    var edges = graph.edges || [];
    state.levelsByHash = computeLevels(commits);
    var colors = getThemeColors();

    var subtitle = document.getElementById('graph-subtitle');
    if (subtitle) {
      subtitle.textContent = commits.length + ' commits · ' + edges.length + ' edges';
    }

    if (commits.length === 0) {
      container.innerHTML = '<div class="empty-state">No commits in repository.</div>';
      return;
    }

    if (!window.vis || !window.vis.Network) {
      renderFallbackSvgDag(container, graph, colors);
      return;
    }

    var visNodes = commits.map(function (c) {
      var isSelected = c.hash === state.selectedCommitHash;
      var style = buildNodeStyle(c, isSelected, colors);
      return Object.assign({
        id: c.hash,
        label: formatNodeLabel(c, graph.head),
        level: state.levelsByHash[c.hash] || 0,
        shape: 'box',
        widthConstraint: { maximum: 205 },
        margin: { top: 9, right: 12, bottom: 9, left: 12 },
        shapeProperties: { borderRadius: 8 }
      }, style);
    });

    var visEdges = edges.map(function (e, i) {
      var style = buildEdgeStyle(e, colors);
      return Object.assign({
        id: 'e-' + i + '-' + e.from + '-' + e.to + '-' + e.idx,
        from: e.from,
        to: e.to,
        idx: e.idx,
        arrows: {
          to: { enabled: true, scaleFactor: 0.7 }
        },
        smooth: {
          enabled: true,
          type: 'cubicBezier',
          forceDirection: 'vertical',
          roundness: 0.45
        }
      }, style);
    });

    state.nodesDataSet = new window.vis.DataSet(visNodes);
    state.edgesDataSet = new window.vis.DataSet(visEdges);

    var options = {
      layout: {
        hierarchical: {
          enabled: true,
          direction: 'UD',
          sortMethod: 'directed',
          levelSeparation: 96,
          nodeSpacing: 245,
          treeSpacing: 260,
          blockShifting: true,
          edgeMinimization: true,
          parentCentralization: true
        }
      },
      physics: {
        enabled: false
      },
      interaction: {
        dragNodes: true,
        dragView: true,
        zoomView: true,
        hover: true,
        selectable: true,
        tooltipDelay: 150
      },
      nodes: {
        shadow: { enabled: false }
      },
      edges: {
        shadow: { enabled: false }
      }
    };

    if (state.network) {
      state.network.destroy();
    }
    state.network = new window.vis.Network(container, {
      nodes: state.nodesDataSet,
      edges: state.edgesDataSet
    }, options);

    state.network.on('click', function (params) {
      if (params.nodes && params.nodes.length > 0) {
        selectCommit(params.nodes[0], { focusGraph: false });
      }
    });

    if (state.selectedCommitHash && state.commitsByHash[state.selectedCommitHash]) {
      state.network.selectNodes([state.selectedCommitHash]);
    }
  }

  function renderFallbackSvgDag(container, graph, colors) {
    var commits = graph.commits || [];
    var edges = graph.edges || [];
    var byLevel = {};
    var maxLevel = 0;
    commits.forEach(function (c) {
      var lv = state.levelsByHash[c.hash] || 0;
      if (lv > maxLevel) {
        maxLevel = lv;
      }
      if (!byLevel[lv]) {
        byLevel[lv] = [];
      }
      byLevel[lv].push(c);
    });

    var width = 640;
    var rowHeight = 86;
    var height = Math.max(300, (maxLevel + 1) * rowHeight + 60);
    var coords = {};

    Object.keys(byLevel).forEach(function (lvKey) {
      var lv = Number(lvKey);
      var row = byLevel[lv];
      var step = width / (row.length + 1);
      row.forEach(function (c, idx) {
        coords[c.hash] = {
          x: Math.round(step * (idx + 1)),
          y: 44 + lv * rowHeight
        };
      });
    });

    var svgParts = [];
    svgParts.push('<svg viewBox="0 0 ' + width + ' ' + height + '" style="width:100%;height:100%;display:block">');
    edges.forEach(function (e) {
      var pFrom = coords[e.from];
      var pTo = coords[e.to];
      if (!pFrom || !pTo) {
        return;
      }
      var dash = e.idx === 1 ? ' stroke-dasharray="5 5"' : '';
      var stroke = e.idx === 1 ? colors.ink3 : colors.accent;
      svgParts.push(
        '<line x1="' + pFrom.x + '" y1="' + pFrom.y + '" x2="' + pTo.x + '" y2="' + pTo.y + '" stroke="' + stroke + '" stroke-width="2"' + dash + '/>'
      );
    });

    commits.forEach(function (c) {
      var pt = coords[c.hash];
      if (!pt) {
        return;
      }
      var isSel = c.hash === state.selectedCommitHash;
      var fill = isSel || c.isHead ? colors.accentSoft : colors.card;
      var stroke = isSel || c.isHead ? colors.accent : colors.line;
      var label = escapeHtml(c.shortHash + ' ' + truncateLine(c.message, 18));
      svgParts.push(
        '<g class="dag-svg-node" data-hash="' + escapeHtml(c.hash) + '" style="cursor:pointer">' +
        '<rect x="' + (pt.x - 85) + '" y="' + (pt.y - 20) + '" width="170" height="40" rx="8" fill="' + fill + '" stroke="' + stroke + '" stroke-width="1.8"/>' +
        '<text x="' + pt.x + '" y="' + (pt.y + 4) + '" text-anchor="middle" fill="' + colors.ink + '" font-family="JetBrains Mono, monospace" font-size="11.5">' + label + '</text>' +
        '</g>'
      );
    });
    svgParts.push('</svg>');
    container.innerHTML = svgParts.join('');
    container.querySelectorAll('.dag-svg-node').forEach(function (nodeEl) {
      nodeEl.addEventListener('click', function () {
        var h = nodeEl.getAttribute('data-hash');
        if (h) {
          selectCommit(h, { focusGraph: false });
        }
      });
    });
  }

  function refreshNetworkTheme() {
    if (!state.graph) {
      return;
    }
    var colors = getThemeColors();
    if (!state.network || !state.nodesDataSet || !state.edgesDataSet) {
      renderGraph(state.graph);
      return;
    }
    var updatedNodes = (state.graph.commits || []).map(function (c) {
      var isSelected = c.hash === state.selectedCommitHash;
      return Object.assign({ id: c.hash }, buildNodeStyle(c, isSelected, colors));
    });
    state.nodesDataSet.update(updatedNodes);

    var updatedEdges = (state.graph.edges || []).map(function (e, i) {
      return Object.assign({
        id: 'e-' + i + '-' + e.from + '-' + e.to + '-' + e.idx
      }, buildEdgeStyle(e, colors));
    });
    state.edgesDataSet.update(updatedEdges);
    if (state.selectedCommitHash && state.commitsByHash[state.selectedCommitHash]) {
      state.network.selectNodes([state.selectedCommitHash]);
    }
    state.network.fit({ animation: false });
  }

  function renderRepoOverview(graph) {
    var headIndicator = document.getElementById('head-indicator');
    if (headIndicator) {
      if (graph.head) {
        var shortHead = graph.headCommit ? graph.headCommit.slice(0, 7) : 'unborn';
        headIndicator.innerHTML = '<b>HEAD</b> -> ' + escapeHtml(graph.head) + ' (' + escapeHtml(shortHead) + ')';
      } else {
        headIndicator.innerHTML = '<b>HEAD</b> detached';
      }
    }

    var refPills = document.getElementById('ref-pills');
    if (refPills) {
      refPills.innerHTML = '';
      (graph.refs || []).forEach(function (ref) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'ref-tag' + (ref.isHead ? ' is-head' : '');
        btn.setAttribute('role', 'listitem');
        btn.setAttribute('data-ref', ref.name);
        btn.setAttribute('data-hash', ref.hash);
        btn.innerHTML =
          '<span>' + (ref.isHead ? '*' : '') + escapeHtml(ref.name) + '</span>' +
          '<span class="ref-short">' + escapeHtml(ref.shortHash || '') + '</span>';
        btn.addEventListener('click', function () {
          if (ref.hash) {
            selectCommit(ref.hash, { focusGraph: true });
          }
        });
        refPills.appendChild(btn);
      });
    }

    var statsEl = document.getElementById('repo-stats');
    if (statsEl) {
      var commitCount = (graph.commits || []).length;
      var refCount = (graph.refs || []).length;
      statsEl.textContent = commitCount + (commitCount === 1 ? ' commit' : ' commits') +
        ' · ' + refCount + (refCount === 1 ? ' branch' : ' branches');
    }
  }

  function renderCommitHistoryList(graph) {
    var listEl = document.getElementById('commit-list');
    var countEl = document.getElementById('history-count');
    var commits = (graph.commits || []).slice().sort(function (a, b) {
      if (b.timestamp !== a.timestamp) {
        return b.timestamp - a.timestamp;
      }
      return a.hash.localeCompare(b.hash);
    });

    if (countEl) {
      countEl.textContent = commits.length + (commits.length === 1 ? ' node' : ' nodes');
    }
    if (!listEl) {
      return;
    }
    listEl.innerHTML = '';

    commits.forEach(function (c) {
      var row = document.createElement('button');
      row.type = 'button';
      row.className = 'commit-row' + (c.hash === state.selectedCommitHash ? ' active' : '');
      row.setAttribute('data-commit-hash', c.hash);
      row.setAttribute('role', 'option');
      row.setAttribute('aria-selected', c.hash === state.selectedCommitHash ? 'true' : 'false');

      var refsHtml = '';
      if (c.isHead) {
        refsHtml += '<span class="mini-tag pro">HEAD</span>';
      }
      (c.refs || []).forEach(function (r) {
        refsHtml += '<span class="mini-tag' + (r === graph.head ? ' pro' : '') + '">' + escapeHtml(r) + '</span>';
      });

      row.innerHTML =
        '<div class="commit-row-left">' +
          '<span class="commit-row-hash">' + escapeHtml(c.shortHash) + '</span>' +
          '<span class="commit-row-msg">' + escapeHtml(truncateLine(c.message, 60)) + '</span>' +
        '</div>' +
        '<div class="commit-row-refs">' + refsHtml + '</div>';

      row.addEventListener('click', function () {
        selectCommit(c.hash, { focusGraph: true });
      });
      listEl.appendChild(row);
    });
  }

  function updateSelectedCommitHighlights(hash, focusGraph) {
    document.querySelectorAll('.commit-row').forEach(function (row) {
      var isMatch = row.getAttribute('data-commit-hash') === hash;
      row.classList.toggle('active', isMatch);
      row.setAttribute('aria-selected', isMatch ? 'true' : 'false');
    });

    if (state.network && state.nodesDataSet) {
      state.network.selectNodes([hash]);
      if (focusGraph === 'center') {
        state.network.focus(hash, {
          scale: Math.max(state.network.getScale(), 0.9),
          animation: { duration: 260, easingFunction: 'easeInOutQuad' }
        });
      }
    } else if (state.graph) {
      var container = document.getElementById('dag-canvas');
      if (container && (!window.vis || !window.vis.Network)) {
        renderFallbackSvgDag(container, state.graph, getThemeColors());
      }
    }
  }

  function selectCommit(hash, opts) {
    if (!hash) {
      return Promise.resolve();
    }
    var options = opts || {};
    state.selectedCommitHash = hash;
    updateSelectedCommitHighlights(hash, Boolean(options.focusGraph));

    return fetch('/api/commits/' + encodeURIComponent(hash))
      .then(function (res) {
        if (!res.ok) {
          throw new Error('Failed to load commit ' + hash);
        }
        return res.json();
      })
      .then(function (detail) {
        state.selectedCommitDetail = detail;
        renderCommitDetail(detail);
        renderFileExplorer(detail);
      })
      .catch(function (err) {
        var msgEl = document.getElementById('detail-message');
        if (msgEl) {
          msgEl.textContent = err.message || 'Unable to load commit details.';
        }
      });
  }

  function renderCommitDetail(detail) {
    var shortPill = document.getElementById('detail-short-pill');
    if (shortPill) {
      shortPill.textContent = detail.shortHash || '';
    }

    var badgesEl = document.getElementById('detail-badges');
    if (badgesEl) {
      var badges = [];
      if (detail.isHead) {
        badges.push('<span class="tag pro">HEAD</span>');
      }
      (detail.refs || []).forEach(function (r) {
        var isCurrentBranch = state.graph && r === state.graph.head;
        badges.push('<span class="tag' + (isCurrentBranch ? ' pro' : '') + '">' + escapeHtml(r) + '</span>');
      });
      var parents = detail.parents || [];
      if (parents.length > 1) {
        badges.push('<span class="tag pro">Merge commit</span>');
      } else if (parents.length === 0) {
        badges.push('<span class="tag">Root commit</span>');
      }
      badgesEl.innerHTML = badges.join('');
    }

    var msgEl = document.getElementById('detail-message');
    if (msgEl) {
      msgEl.textContent = detail.message || '(no message)';
    }

    var shortHashEl = document.getElementById('detail-short-hash');
    if (shortHashEl) {
      shortHashEl.textContent = detail.shortHash || '-';
    }

    var fullHashEl = document.getElementById('detail-full-hash');
    if (fullHashEl) {
      fullHashEl.textContent = detail.hash || '-';
    }

    var dateEl = document.getElementById('detail-date');
    var epochEl = document.getElementById('detail-epoch');
    if (dateEl) {
      dateEl.textContent = detail.date || new Date(detail.timestamp || 0).toUTCString();
    }
    if (epochEl) {
      epochEl.textContent = '(' + Number(detail.timestamp || 0) + ' ms)';
    }

    var parentsEl = document.getElementById('detail-parents');
    if (parentsEl) {
      parentsEl.innerHTML = '';
      var parentList = detail.parents || [];
      if (parentList.length === 0) {
        var noneSpan = document.createElement('span');
        noneSpan.className = 'meta-dim';
        noneSpan.textContent = 'None (initial commit)';
        parentsEl.appendChild(noneSpan);
      } else {
        parentList.forEach(function (parentHash, idx) {
          var chip = document.createElement('button');
          chip.type = 'button';
          chip.className = 'parent-chip';
          chip.setAttribute('data-parent-hash', parentHash);
          var shortParent = parentHash.slice(0, 7);
          var parentCommit = state.commitsByHash[parentHash];
          var parentSummary = parentCommit ? ' · ' + truncateLine(parentCommit.message, 22) : '';
          var roleLabel = idx === 0 ? 'p0' : 'p1 (merge)';
          chip.innerHTML =
            '<span class="p-idx">' + escapeHtml(roleLabel) + '</span>' +
            '<span>' + escapeHtml(shortParent) + escapeHtml(parentSummary) + '</span>';
          chip.addEventListener('click', function () {
            selectCommit(parentHash, { focusGraph: true });
          });
          parentsEl.appendChild(chip);
        });
      }
    }
  }

  function buildHierarchyTree(files) {
    var root = { name: '', dirs: {}, files: [] };
    files.forEach(function (fileObj) {
      var parts = String(fileObj.path).split('/').filter(Boolean);
      var cursor = root;
      for (var i = 0; i < parts.length - 1; i++) {
        var seg = parts[i];
        if (!cursor.dirs[seg]) {
          cursor.dirs[seg] = { name: seg, dirs: {}, files: [] };
        }
        cursor = cursor.dirs[seg];
      }
      cursor.files.push({
        name: parts.length > 0 ? parts[parts.length - 1] : fileObj.path,
        path: fileObj.path,
        blobHash: fileObj.blobHash,
        shortBlobHash: fileObj.shortBlobHash,
        size: fileObj.size
      });
    });
    return root;
  }

  function renderTreeNode(node, container, commitHash) {
    var dirNames = Object.keys(node.dirs).sort();
    dirNames.forEach(function (dirName) {
      var childDir = node.dirs[dirName];
      var dirWrap = document.createElement('div');
      dirWrap.className = 'tree-dir';

      var label = document.createElement('div');
      label.className = 'tree-dir-label';
      label.innerHTML = '<span class="tree-file-icon">▾</span><span>' + escapeHtml(dirName) + '/</span>';

      var childrenBox = document.createElement('div');
      childrenBox.className = 'tree-dir-children';

      label.addEventListener('click', function () {
        var collapsed = dirWrap.classList.toggle('collapsed');
        var icon = label.querySelector('.tree-file-icon');
        if (icon) {
          icon.textContent = collapsed ? '▸' : '▾';
        }
      });

      dirWrap.appendChild(label);
      renderTreeNode(childDir, childrenBox, commitHash);
      dirWrap.appendChild(childrenBox);
      container.appendChild(dirWrap);
    });

    node.files.forEach(function (fileObj) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'tree-file' + (fileObj.path === state.selectedFilePath ? ' active' : '');
      btn.setAttribute('role', 'treeitem');
      btn.setAttribute('data-file-path', fileObj.path);
      btn.innerHTML =
        '<span class="tree-file-name">' +
          '<span class="tree-file-icon">≡</span>' +
          '<span>' + escapeHtml(fileObj.name) + '</span>' +
        '</span>' +
        '<span class="tree-file-hash">' + escapeHtml(fileObj.shortBlobHash || '') + '</span>';
      btn.addEventListener('click', function () {
        selectFile(commitHash, fileObj.path);
      });
      container.appendChild(btn);
    });
  }

  function renderFileExplorer(detail) {
    var files = detail.files || [];
    var summaryEl = document.getElementById('files-summary');
    if (summaryEl) {
      summaryEl.textContent = files.length + (files.length === 1 ? ' tracked file' : ' tracked files');
    }

    var treeEl = document.getElementById('file-tree');
    if (!treeEl) {
      return;
    }
    treeEl.innerHTML = '';

    if (files.length === 0) {
      state.selectedFilePath = null;
      treeEl.innerHTML = '<div class="empty-state">No tracked files in this commit.</div>';
      var pathEl = document.getElementById('blob-path');
      var chipEl = document.getElementById('blob-hash-chip');
      var metaEl = document.getElementById('blob-header-meta');
      var viewerEl = document.getElementById('blob-viewer');
      if (pathEl) {
        pathEl.textContent = 'Empty tree';
      }
      if (chipEl) {
        chipEl.textContent = '';
      }
      if (metaEl) {
        metaEl.textContent = '';
      }
      if (viewerEl) {
        viewerEl.innerHTML = '<div class="empty-state">This commit has an empty file snapshot (0 tracked files).</div>';
      }
      return;
    }

    var targetPath = null;
    if (state.selectedFilePath) {
      var stillExists = files.some(function (f) {
        return f.path === state.selectedFilePath;
      });
      if (stillExists) {
        targetPath = state.selectedFilePath;
      }
    }
    if (!targetPath) {
      targetPath = files[0].path;
    }
    state.selectedFilePath = targetPath;

    var hierarchy = buildHierarchyTree(files);
    renderTreeNode(hierarchy, treeEl, detail.hash);
    selectFile(detail.hash, targetPath);
  }

  function selectFile(commitHash, filePath) {
    state.selectedFilePath = filePath;
    document.querySelectorAll('.tree-file').forEach(function (btn) {
      btn.classList.toggle('active', btn.getAttribute('data-file-path') === filePath);
    });

    var pathEl = document.getElementById('blob-path');
    if (pathEl) {
      pathEl.textContent = filePath;
    }

    var url = '/api/commits/' + encodeURIComponent(commitHash) + '/blob?path=' + encodeURIComponent(filePath);
    return fetch(url)
      .then(function (res) {
        if (!res.ok) {
          throw new Error('Failed to load blob for ' + filePath);
        }
        return res.json();
      })
      .then(function (blob) {
        renderBlobPreview(blob);
      })
      .catch(function (err) {
        var viewerEl = document.getElementById('blob-viewer');
        if (viewerEl) {
          viewerEl.innerHTML = '<div class="empty-state">' + escapeHtml(err.message || 'Failed to load blob.') + '</div>';
        }
      });
  }

  function formatBytes(bytes) {
    var n = Number(bytes || 0);
    if (n < 1024) {
      return n + ' B';
    }
    if (n < 1024 * 1024) {
      return (n / 1024).toFixed(1) + ' KB';
    }
    return (n / (1024 * 1024)).toFixed(2) + ' MB';
  }

  function renderBlobPreview(blob) {
    var pathEl = document.getElementById('blob-path');
    var chipEl = document.getElementById('blob-hash-chip');
    var metaEl = document.getElementById('blob-header-meta');
    var viewerEl = document.getElementById('blob-viewer');

    if (pathEl) {
      pathEl.textContent = blob.path || '';
    }
    if (chipEl) {
      chipEl.textContent = 'blob ' + (blob.shortBlobHash || '');
    }

    if (!viewerEl) {
      return;
    }

    if (blob.binary) {
      if (metaEl) {
        metaEl.textContent = 'binary · ' + formatBytes(blob.size);
      }
      viewerEl.innerHTML =
        '<div class="binary-box">' +
          '<p class="binary-title">Binary blob (' + escapeHtml(formatBytes(blob.size)) + ' · ' + escapeHtml(blob.shortBlobHash || '') + ')</p>' +
          '<pre class="binary-hex">' + escapeHtml(blob.hexPreview || '') + '</pre>' +
        '</div>';
      return;
    }

    var content = typeof blob.content === 'string' ? blob.content : '';
    if (content.length === 0) {
      if (metaEl) {
        metaEl.textContent = '0 lines · 0 B';
      }
      viewerEl.innerHTML = '<div class="empty-state">Empty file (0 bytes).</div>';
      return;
    }

    var rawLines = content.split(/\r?\n/);
    if (rawLines.length > 1 && rawLines[rawLines.length - 1] === '' && content.endsWith('\n')) {
      rawLines.pop();
    }
    if (metaEl) {
      metaEl.textContent = rawLines.length + (rawLines.length === 1 ? ' line' : ' lines') + ' · ' + formatBytes(blob.size);
    }

    var rowsHtml = rawLines.map(function (line, idx) {
      return '<tr><td class="ln">' + (idx + 1) + '</td><td class="lc">' + escapeHtml(line) + '</td></tr>';
    }).join('');

    viewerEl.innerHTML = '<table class="code-table"><tbody>' + rowsHtml + '</tbody></table>';
  }

  function escapeHtml(str) {
    return String(str == null ? '' : str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function loadRepository() {
    return fetch('/api/graph')
      .then(function (res) {
        if (!res.ok) {
          throw new Error('Failed to load repository graph');
        }
        return res.json();
      })
      .then(function (graph) {
        state.graph = graph;
        state.commitsByHash = {};
        (graph.commits || []).forEach(function (c) {
          state.commitsByHash[c.hash] = c;
        });

        var initialTarget = state.selectedCommitHash && state.commitsByHash[state.selectedCommitHash]
          ? state.selectedCommitHash
          : (graph.headCommit || (graph.commits && graph.commits.length > 0 ? graph.commits[graph.commits.length - 1].hash : null));

        state.selectedCommitHash = initialTarget;
        renderRepoOverview(graph);
        renderCommitHistoryList(graph);
        renderGraph(graph);

        if (initialTarget) {
          return selectCommit(initialTarget, { focusGraph: false });
        }
      })
      .catch(function (err) {
        var container = document.getElementById('dag-canvas');
        if (container) {
          container.innerHTML = '<div class="empty-state">' + escapeHtml(err.message || 'Unable to load repository.') + '</div>';
        }
      });
  }

  function bindToolbarControls() {
    var btnRefresh = document.getElementById('btn-refresh');
    if (btnRefresh) {
      btnRefresh.addEventListener('click', function () {
        loadRepository();
      });
    }

    var btnFocusHead = document.getElementById('btn-focus-head');
    if (btnFocusHead) {
      btnFocusHead.addEventListener('click', function () {
        if (state.graph && state.graph.headCommit) {
          selectCommit(state.graph.headCommit, { focusGraph: true });
        }
      });
    }

    var btnFit = document.getElementById('btn-fit-graph');
    if (btnFit) {
      btnFit.addEventListener('click', function () {
        if (state.network) {
          state.network.fit({ animation: { duration: 260, easingFunction: 'easeInOutQuad' } });
        }
      });
    }

    var btnZoomIn = document.getElementById('btn-zoom-in');
    if (btnZoomIn) {
      btnZoomIn.addEventListener('click', function () {
        if (state.network) {
          var scale = state.network.getScale();
          state.network.moveTo({ scale: Math.min(scale * 1.25, 3.0) });
        }
      });
    }

    var btnZoomOut = document.getElementById('btn-zoom-out');
    if (btnZoomOut) {
      btnZoomOut.addEventListener('click', function () {
        if (state.network) {
          var scale = state.network.getScale();
          state.network.moveTo({ scale: Math.max(scale / 1.25, 0.25) });
        }
      });
    }

    var btnCopy = document.getElementById('btn-copy-hash');
    if (btnCopy) {
      btnCopy.addEventListener('click', function () {
        var hash = state.selectedCommitDetail && state.selectedCommitDetail.hash;
        if (!hash) {
          return;
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(hash).then(function () {
            btnCopy.textContent = 'Copied';
            setTimeout(function () {
              btnCopy.textContent = 'Copy full hash';
            }, 1200);
          }).catch(function () {});
        }
      });
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    initTheme();
    bindToolbarControls();
    loadRepository();
  });
})();
