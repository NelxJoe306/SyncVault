(function () {
  'use strict';

  var state = {
    graph: null,
    commitsByHash: {},
    selectedCommitHash: null,
    selectedCommitDetail: null,
    selectedFilePath: null,
    selectedBlob: null,
    blobViewMode: 'conflict',
    network: null,
    nodesDataSet: null,
    edgesDataSet: null,
    levelsByHash: {},
    branchByHash: {},
    laneByBranch: {},
    laneByHash: {},
    coordsByHash: {},
    laneSpacing: 145,
    levelSeparation: 84
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
      isDark: isDark,
      paper: token('--paper', isDark ? '#0c0d10' : '#fbfbfa'),
      card: token('--card', isDark ? '#14161a' : '#ffffff'),
      sunk: token('--sunk', isDark ? '#101216' : '#f3f4f4'),
      ink: token('--ink', isDark ? '#f4f5f6' : '#0f1112'),
      ink2: token('--ink-2', isDark ? '#c3c8cd' : '#3a3f44'),
      ink3: token('--ink-3', isDark ? '#8b9198' : '#6b7177'),
      line: isDark ? 'rgba(244,245,246,0.22)' : 'rgba(15,17,18,0.18)',
      accent: token('--accent', isDark ? '#9cc9da' : '#4f7a8c'),
      accentSoft: token('--accent-soft', isDark ? '#1b2a31' : '#e3edf1'),
      mergeBorder: token('--merge-border', isDark ? '#8aa8b8' : '#5b6d78'),
      mergeSoft: token('--merge-soft', isDark ? '#182128' : '#eaeff2'),
      branchPalettes: [
        {
          key: 'sage',
          border: token('--b-sage', isDark ? '#7ec49e' : '#3d7a5a'),
          bg: token('--b-sage-soft', isDark ? '#162820' : '#e5f2eb')
        },
        {
          key: 'rose',
          border: token('--b-rose', isDark ? '#e09385' : '#a85548'),
          bg: token('--b-rose-soft', isDark ? '#2c1b19' : '#f7e8e5')
        },
        {
          key: 'amber',
          border: token('--b-amber', isDark ? '#dfc07a' : '#9a7328'),
          bg: token('--b-amber-soft', isDark ? '#2a2315' : '#f7efe0')
        },
        {
          key: 'plum',
          border: token('--b-plum', isDark ? '#b9a0d6' : '#755994'),
          bg: token('--b-plum-soft', isDark ? '#221c2b' : '#efeaf5')
        },
        {
          key: 'indigo',
          border: token('--b-indigo', isDark ? '#94b0e0' : '#4d6899'),
          bg: token('--b-indigo-soft', isDark ? '#192231' : '#e7ecf5')
        }
      ]
    };
  }

  function getBranchPalette(branchName, colors) {
    var primaryBranch = (state.graph && state.graph.head) || 'master';
    var name = String(branchName || primaryBranch);
    if (name === primaryBranch || name === 'master' || name === 'main' || name === 'HEAD') {
      return {
        isPrimary: true,
        border: colors.accent,
        bg: colors.accentSoft
      };
    }
    var lower = name.toLowerCase();
    var list = colors.branchPalettes;
    var slot = -1;
    if (lower.indexOf('feat') >= 0) {
      slot = 0;
    } else if (lower.indexOf('hotfix') >= 0 || lower.indexOf('fix') >= 0 || lower.indexOf('bug') >= 0) {
      slot = 1;
    } else if (lower.indexOf('doc') >= 0) {
      slot = 2;
    } else if (lower.indexOf('exp') >= 0 || lower.indexOf('wip') >= 0 || lower.indexOf('proto') >= 0) {
      slot = 3;
    } else if (lower.indexOf('rel') >= 0 || lower.indexOf('dev') >= 0 || lower.indexOf('stage') >= 0) {
      slot = 4;
    } else {
      var hash = 0;
      for (var i = 0; i < lower.length; i++) {
        hash = ((hash * 31) + lower.charCodeAt(i)) >>> 0;
      }
      slot = hash % list.length;
    }
    var chosen = list[slot] || list[0];
    return {
      isPrimary: false,
      border: chosen.border,
      bg: chosen.bg
    };
  }

  function computeGraphLayout(graph) {
    var commits = (graph && graph.commits) || [];
    var byHash = {};
    var childrenOf = {};
    commits.forEach(function (c) {
      byHash[c.hash] = c;
      childrenOf[c.hash] = [];
    });
    commits.forEach(function (c) {
      (c.parents || []).forEach(function (p) {
        if (childrenOf[p]) {
          childrenOf[p].push(c.hash);
        }
      });
    });

    var primaryBranch = (graph && graph.head) || 'master';
    var spine = [];
    var branchOf = {};
    var spineIndex = {};
    var startHash = (graph && graph.headCommit && byHash[graph.headCommit])
      ? graph.headCommit
      : (commits.length > 0 ? commits[commits.length - 1].hash : null);

    var curr = startHash;
    while (curr && byHash[curr] && !Object.prototype.hasOwnProperty.call(spineIndex, curr)) {
      spineIndex[curr] = spine.length;
      spine.push(curr);
      branchOf[curr] = primaryBranch;
      var parents = byHash[curr].parents;
      curr = (parents && parents.length > 0) ? parents[0] : null;
    }

    var mergeTargetOf = {};
    commits.forEach(function (c) {
      if (Array.isArray(c.parents) && c.parents.length > 1) {
        for (var i = 1; i < c.parents.length; i++) {
          mergeTargetOf[c.parents[i]] = c.hash;
        }
      }
    });

    var sideBranches = [];
    var refs = ((graph && graph.refs) || []).slice().sort(function (a, b) {
      return String(a.name).localeCompare(String(b.name));
    });

    function registerSideBranch(branchName, tipHash) {
      if (!tipHash || !byHash[tipHash] || branchOf[tipHash]) {
        return;
      }
      var chain = [];
      var h = tipHash;
      while (h && byHash[h] && !branchOf[h]) {
        branchOf[h] = branchName;
        chain.push(h);
        var p = byHash[h].parents;
        h = (p && p.length > 0) ? p[0] : null;
      }
      var baseHash = h;
      var mergeHash = mergeTargetOf[tipHash] || null;
      var baseIdx = (baseHash && Object.prototype.hasOwnProperty.call(spineIndex, baseHash))
        ? spineIndex[baseHash]
        : spine.length;
      var topIdx = (mergeHash && Object.prototype.hasOwnProperty.call(spineIndex, mergeHash))
        ? spineIndex[mergeHash]
        : (baseIdx - 0.5);
      var span = Math.max(0.5, baseIdx - topIdx);
      sideBranches.push({
        name: branchName,
        tip: tipHash,
        chain: chain,
        baseHash: baseHash,
        mergeHash: mergeHash,
        span: span
      });
    }

    refs.forEach(function (ref) {
      if (ref.name !== primaryBranch) {
        registerSideBranch(ref.name, ref.hash);
      }
    });

    // Also cover any merged second-parent chains whose branch ref was removed
    commits.forEach(function (c) {
      if (Array.isArray(c.parents) && c.parents.length > 1) {
        for (var i = 1; i < c.parents.length; i++) {
          var p2 = c.parents[i];
          if (byHash[p2] && !branchOf[p2]) {
            var match = /^Merged\s+([^\s]+)\s+into\s+/i.exec(c.message || '');
            var inferred = match ? match[1] : ('branch-' + p2.slice(0, 5));
            registerSideBranch(inferred, p2);
          }
        }
      }
    });

    // Cover any remaining detached commits
    commits.slice().sort(function (a, b) {
      return (b.timestamp || 0) - (a.timestamp || 0);
    }).forEach(function (c) {
      if (!branchOf[c.hash]) {
        var name = (c.refs && c.refs[0]) || ('detached-' + c.shortHash);
        registerSideBranch(name, c.hash);
      }
    });

    sideBranches.sort(function (a, b) {
      if (a.span !== b.span) {
        return a.span - b.span;
      }
      if (a.chain.length !== b.chain.length) {
        return a.chain.length - b.chain.length;
      }
      return String(a.name).localeCompare(String(b.name));
    });

    var laneByBranch = {};
    laneByBranch[primaryBranch] = 0;
    var lanePattern = [-1, 1, 2, -2, -3, 3, 4, -4];
    sideBranches.forEach(function (b, idx) {
      var lane = idx < lanePattern.length
        ? lanePattern[idx]
        : (idx % 2 === 0 ? -(Math.floor(idx / 2) + 1) : (Math.floor(idx / 2) + 1));
      laneByBranch[b.name] = lane;
    });

    var laneByHash = {};
    commits.forEach(function (c) {
      var bName = branchOf[c.hash] || primaryBranch;
      laneByHash[c.hash] = laneByBranch[bName] || 0;
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
        c.parents.forEach(function (p, idx) {
          if (!byHash[p]) {
            return;
          }
          var step = 1;
          if (childrenOf[p] && childrenOf[p].length >= 3) {
            var absLane = Math.abs(laneByHash[hash] || 0);
            if (absLane % 2 === 1 || (c.parents.length > 1 && idx === 0)) {
              step = 2;
            }
          }
          var pr = rank(p) + step;
          if (pr > r) {
            r = pr;
          }
        });
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

    // Stagger side-branch nodes within their topological slack window so parallel branches do not collide
    sideBranches.forEach(function (b) {
      b.chain.forEach(function (h) {
        var c = byHash[h];
        if (!c || !c.parents || c.parents.length === 0) {
          return;
        }
        var myLane = laneByHash[h];
        var parentLevels = c.parents
          .filter(function (p) { return Object.prototype.hasOwnProperty.call(levels, p); })
          .map(function (p) { return levels[p]; });
        if (parentLevels.length === 0) {
          return;
        }
        var parentLv = Math.min.apply(null, parentLevels);
        var childLevels = (childrenOf[h] || [])
          .filter(function (ch) { return Object.prototype.hasOwnProperty.call(levels, ch); })
          .map(function (ch) { return levels[ch]; });
        var minLv = childLevels.length > 0
          ? (Math.max.apply(null, childLevels) + 1)
          : Math.max(0, parentLv - 2);
        var maxLv = parentLv - 1;
        if (minLv <= maxLv) {
          var bestLv = levels[h];
          var bestScore = 1e9;
          for (var lv = minLv; lv <= maxLv; lv++) {
            var score = 0;
            commits.forEach(function (other) {
              if (other.hash === h) {
                return;
              }
              var oLv = levels[other.hash];
              var oLane = laneByHash[other.hash];
              if (oLv === lv) {
                score += 2;
                if (Math.abs(oLane - myLane) <= 1) {
                  score += 100;
                }
              }
              if (myLane * oLane > 0 && Math.abs(oLane) < Math.abs(myLane)) {
                if (lv <= oLv && parentLv > oLv) {
                  score += 40;
                }
              }
            });
            if (Math.abs(myLane) % 2 === lv % 2) {
              score -= 3;
            }
            if (score < bestScore) {
              bestScore = score;
              bestLv = lv;
            }
          }
          levels[h] = bestLv;
        }
      });
    });

    // Check minimum lane distance between any two commits on the same horizontal level
    var minSameLevelLaneDiff = 99;
    for (var i = 0; i < commits.length; i++) {
      for (var j = i + 1; j < commits.length; j++) {
        var h1 = commits[i].hash;
        var h2 = commits[j].hash;
        if (levels[h1] === levels[h2]) {
          var diff = Math.abs((laneByHash[h1] || 0) - (laneByHash[h2] || 0));
          if (diff > 0 && diff < minSameLevelLaneDiff) {
            minSameLevelLaneDiff = diff;
          }
        }
      }
    }

    var laneSpacing = minSameLevelLaneDiff >= 2 ? 168 : 260;
    var levelSeparation = 78;
    var coords = {};
    commits.forEach(function (c) {
      var ln = laneByHash[c.hash] || 0;
      var lv = levels[c.hash] || 0;
      coords[c.hash] = {
        lane: ln,
        level: lv,
        x: ln * laneSpacing,
        y: lv * levelSeparation
      };
    });

    state.levelsByHash = levels;
    state.branchByHash = branchOf;
    state.laneByBranch = laneByBranch;
    state.laneByHash = laneByHash;
    state.coordsByHash = coords;
    state.laneSpacing = laneSpacing;
    state.levelSeparation = levelSeparation;
    return levels;
  }

  function computeLevels(commits) {
    return computeGraphLayout({
      commits: commits,
      refs: (state.graph && state.graph.refs) || [],
      head: (state.graph && state.graph.head) || 'master',
      headCommit: state.graph && state.graph.headCommit
    });
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

  function escapeVisHtml(str) {
    return String(str == null ? '' : str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;');
  }

  function formatNodeLabel(commit, headBranch) {
    var badges = [];
    var refs = Array.isArray(commit.refs) ? commit.refs : [];
    var isMerge = Array.isArray(commit.parents) && commit.parents.length > 1;
    var branchName = state.branchByHash[commit.hash] || headBranch || 'master';

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
    } else if (isMerge) {
      badges.push('merge');
    } else if (branchName && branchName !== (headBranch || 'master')) {
      badges.push(branchName);
    }

    var header = '<b>' + escapeVisHtml(commit.shortHash) + '</b>';
    if (badges.length > 0) {
      header += '  [' + escapeVisHtml(badges.join(', ')) + ']';
    }
    var msgLine = escapeVisHtml(truncateLine(commit.message, 30));
    return header + '\n' + msgLine;
  }

  function formatNodeTitle(commit, headBranch) {
    var branchName = state.branchByHash[commit.hash] || headBranch || 'master';
    var parts = [
      commit.shortHash + ' · branch: ' + branchName,
      String(commit.message || '').trim()
    ];
    if (commit.date) {
      parts.push(commit.date);
    }
    return parts.join('\n');
  }

  function buildNodeStyle(commit, isSelected, colors) {
    var primaryBranch = (state.graph && state.graph.head) || 'master';
    var branchName = state.branchByHash[commit.hash] || primaryBranch;
    var pal = getBranchPalette(branchName, colors);
    var hasRef = commit.isHead || (Array.isArray(commit.refs) && commit.refs.length > 0);
    var isMerge = Array.isArray(commit.parents) && commit.parents.length > 1;

    var bg = colors.card;
    var border = pal.border;
    var borderWidth = 1.6;
    var boldColor = pal.border;
    var borderDashes = false;

    if (commit.isHead) {
      bg = colors.accentSoft;
      border = colors.accent;
      borderWidth = 2.8;
      boldColor = colors.accent;
    } else if (hasRef) {
      bg = pal.bg;
      border = pal.border;
      borderWidth = 2.3;
      boldColor = pal.border;
    } else if (isMerge) {
      bg = colors.mergeSoft;
      border = colors.mergeBorder;
      borderWidth = 2.1;
      boldColor = colors.mergeBorder;
      borderDashes = [6, 3];
    } else if (!pal.isPrimary) {
      bg = pal.bg;
      border = pal.border;
      borderWidth = 1.75;
      boldColor = pal.border;
    } else {
      bg = colors.card;
      border = colors.accent;
      borderWidth = 1.5;
      boldColor = colors.accent;
    }

    return {
      color: {
        background: bg,
        border: border,
        highlight: {
          background: pal.isPrimary ? colors.accentSoft : pal.bg,
          border: pal.border
        },
        hover: {
          background: pal.isPrimary ? colors.accentSoft : pal.bg,
          border: pal.border
        }
      },
      borderWidth: borderWidth,
      borderWidthSelected: 3.0,
      shapeProperties: {
        borderRadius: 8,
        borderDashes: borderDashes
      },
      font: {
        color: colors.ink,
        face: '"JetBrains Mono", "Noto Sans SC", ui-monospace, monospace',
        size: 13.5,
        multi: 'html',
        bold: {
          color: boldColor,
          face: '"JetBrains Mono", "Noto Sans SC", ui-monospace, monospace',
          size: 13.5,
          mod: 'bold'
        },
        align: 'left'
      }
    };
  }

  function buildEdgeStyle(edge, colors) {
    var isMergeSecondParent = edge.idx === 1;
    var targetBranch = state.branchByHash[isMergeSecondParent ? edge.to : edge.from]
      || (state.graph && state.graph.head)
      || 'master';
    var pal = getBranchPalette(targetBranch, colors);
    var strokeColor = isMergeSecondParent
      ? (pal.isPrimary ? colors.ink3 : pal.border)
      : pal.border;
    return {
      color: {
        color: strokeColor,
        highlight: pal.border,
        hover: pal.border
      },
      dashes: isMergeSecondParent ? [6, 5] : false,
      width: isMergeSecondParent ? 2.0 : 2.3
    };
  }

  function applyBranchLanePositions() {
    if (!state.network || !state.graph) {
      return;
    }
    var commits = state.graph.commits || [];
    commits.forEach(function (c) {
      var pos = state.coordsByHash[c.hash];
      if (pos) {
        state.network.moveNode(c.hash, pos.x, pos.y);
      }
    });
    state.network.redraw();
  }

  function fitGraph(opts) {
    if (!state.network) {
      return;
    }
    var options = opts || {};
    state.network.fit({
      minZoomLevel: options.minZoomLevel || 0.62,
      maxZoomLevel: options.maxZoomLevel || 1.08,
      animation: options.animation || false
    });
  }

  function renderGraph(graph) {
    var container = document.getElementById('dag-canvas');
    if (!container) {
      return;
    }
    var commits = graph.commits || [];
    var edges = graph.edges || [];
    if (!state.coordsByHash || Object.keys(state.coordsByHash).length !== commits.length) {
      computeGraphLayout(graph);
    }
    var colors = getThemeColors();

    var subtitle = document.getElementById('graph-subtitle');
    if (subtitle) {
      var branchCount = Object.keys(state.laneByBranch || {}).length || 1;
      subtitle.textContent = commits.length + ' commits · ' + branchCount + ' lanes · ' + edges.length + ' edges';
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
      var pos = state.coordsByHash[c.hash] || { x: 0, y: 0, level: 0 };
      return Object.assign({
        id: c.hash,
        label: formatNodeLabel(c, graph.head),
        title: formatNodeTitle(c, graph.head),
        level: pos.level,
        x: pos.x,
        y: pos.y,
        shape: 'box',
        widthConstraint: { minimum: 160, maximum: 258 },
        margin: { top: 8, right: 11, bottom: 8, left: 11 }
      }, style);
    });

    var visEdges = edges.map(function (e, i) {
      var style = buildEdgeStyle(e, colors);
      var fromPos = state.coordsByHash[e.from] || { lane: 0, level: 0 };
      var toPos = state.coordsByHash[e.to] || { lane: 0, level: 0 };
      var laneDelta = toPos.lane - fromPos.lane;
      var levelDelta = Math.abs(toPos.level - fromPos.level);
      var smoothCfg;
      if (Math.abs(laneDelta) >= 2 && levelDelta >= 2) {
        smoothCfg = {
          enabled: true,
          type: laneDelta < 0 ? 'curvedCCW' : 'curvedCW',
          roundness: 0.34
        };
      } else {
        smoothCfg = {
          enabled: true,
          type: 'cubicBezier',
          forceDirection: 'vertical',
          roundness: 0.42
        };
      }
      return Object.assign({
        id: 'e-' + i + '-' + e.from + '-' + e.to + '-' + e.idx,
        from: e.from,
        to: e.to,
        idx: e.idx,
        arrows: {
          to: { enabled: true, scaleFactor: 0.68 }
        },
        smooth: smoothCfg
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
          levelSeparation: state.levelSeparation,
          nodeSpacing: state.laneSpacing,
          treeSpacing: state.laneSpacing,
          blockShifting: false,
          edgeMinimization: false,
          parentCentralization: false
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
        tooltipDelay: 120
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

    applyBranchLanePositions();
    fitGraph({ animation: false, minZoomLevel: 0.65, maxZoomLevel: 1.05 });

    if (window.requestAnimationFrame) {
      window.requestAnimationFrame(function () {
        applyBranchLanePositions();
        fitGraph({ animation: false, minZoomLevel: 0.65, maxZoomLevel: 1.05 });
      });
    }
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(function () {
        if (state.network) {
          applyBranchLanePositions();
          fitGraph({ animation: false, minZoomLevel: 0.65, maxZoomLevel: 1.05 });
        }
      }).catch(function () {});
    }

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
    var minX = 0;
    var maxX = 0;
    var maxLevel = 0;
    commits.forEach(function (c) {
      var pos = state.coordsByHash[c.hash] || { x: 0, level: 0 };
      if (pos.x < minX) {
        minX = pos.x;
      }
      if (pos.x > maxX) {
        maxX = pos.x;
      }
      if (pos.level > maxLevel) {
        maxLevel = pos.level;
      }
    });

    var padX = 130;
    var rowHeight = state.levelSeparation || 84;
    var width = Math.max(640, (maxX - minX) + padX * 2);
    var height = Math.max(360, (maxLevel + 1) * rowHeight + 60);
    var offsetX = Math.round((width - (maxX - minX)) / 2) - minX;
    var coords = {};

    commits.forEach(function (c) {
      var pos = state.coordsByHash[c.hash] || { x: 0, level: 0 };
      coords[c.hash] = {
        x: Math.round(pos.x + offsetX),
        y: 44 + pos.level * rowHeight
      };
    });

    var svgParts = [];
    svgParts.push('<svg viewBox="0 0 ' + width + ' ' + height + '" style="width:100%;height:100%;display:block">');
    edges.forEach(function (e) {
      var pFrom = coords[e.from];
      var pTo = coords[e.to];
      if (!pFrom || !pTo) {
        return;
      }
      var eStyle = buildEdgeStyle(e, colors);
      var dash = e.idx === 1 ? ' stroke-dasharray="6 5"' : '';
      var stroke = eStyle.color.color;
      svgParts.push(
        '<line x1="' + pFrom.x + '" y1="' + pFrom.y + '" x2="' + pTo.x + '" y2="' + pTo.y + '" stroke="' + stroke + '" stroke-width="2.2"' + dash + '/>'
      );
    });

    commits.forEach(function (c) {
      var pt = coords[c.hash];
      if (!pt) {
        return;
      }
      var isSel = c.hash === state.selectedCommitHash;
      var nStyle = buildNodeStyle(c, isSel, colors);
      var fill = nStyle.color.background;
      var stroke = nStyle.color.border;
      var labelTop = escapeHtml(c.shortHash + (c.refs && c.refs.length ? ' [' + c.refs.join(', ') + ']' : ''));
      var labelBottom = escapeHtml(truncateLine(c.message, 26));
      svgParts.push(
        '<g class="dag-svg-node" data-hash="' + escapeHtml(c.hash) + '" style="cursor:pointer">' +
        '<rect x="' + (pt.x - 95) + '" y="' + (pt.y - 23) + '" width="190" height="46" rx="8" fill="' + fill + '" stroke="' + stroke + '" stroke-width="2"/>' +
        '<text x="' + (pt.x - 84) + '" y="' + (pt.y - 4) + '" fill="' + stroke + '" font-family="JetBrains Mono, monospace" font-weight="700" font-size="12.5">' + labelTop + '</text>' +
        '<text x="' + (pt.x - 84) + '" y="' + (pt.y + 14) + '" fill="' + colors.ink + '" font-family="JetBrains Mono, monospace" font-size="12.5">' + labelBottom + '</text>' +
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
    renderRepoOverview(state.graph);
    renderCommitHistoryList(state.graph);
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
    applyBranchLanePositions();
    if (state.selectedCommitHash && state.commitsByHash[state.selectedCommitHash]) {
      state.network.selectNodes([state.selectedCommitHash]);
    }
    fitGraph({ animation: false, minZoomLevel: 0.65, maxZoomLevel: 1.05 });
  }

  function renderRepoOverview(graph) {
    var colors = getThemeColors();
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
        var pal = getBranchPalette(ref.name, colors);
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'ref-tag' + (ref.isHead ? ' is-head' : '');
        btn.setAttribute('role', 'listitem');
        btn.setAttribute('data-ref', ref.name);
        btn.setAttribute('data-hash', ref.hash);
        if (!ref.isHead) {
          btn.style.borderColor = pal.border;
          btn.style.backgroundColor = pal.bg;
        }
        btn.innerHTML =
          '<span class="ref-dot" style="background:' + escapeHtml(pal.border) + '"></span>' +
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
    var colors = getThemeColors();
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

      var branchName = state.branchByHash[c.hash] || graph.head || 'master';
      var branchPal = getBranchPalette(branchName, colors);

      var refsHtml = '';
      if (c.isHead) {
        refsHtml += '<span class="mini-tag pro">HEAD</span>';
      }
      (c.refs || []).forEach(function (r) {
        var rPal = getBranchPalette(r, colors);
        var styleAttr = r === graph.head
          ? ''
          : ' style="border-color:' + escapeHtml(rPal.border) + ';color:' + escapeHtml(rPal.border) + ';background:' + escapeHtml(rPal.bg) + '"';
        refsHtml += '<span class="mini-tag' + (r === graph.head ? ' pro' : '') + '"' + styleAttr + '>' + escapeHtml(r) + '</span>';
      });

      var hashStyle = !branchPal.isPrimary
        ? ' style="color:' + escapeHtml(branchPal.border) + ';border-color:' + escapeHtml(branchPal.border) + '"'
        : '';

      row.innerHTML =
        '<div class="commit-row-left">' +
          '<span class="commit-row-hash"' + hashStyle + '>' + escapeHtml(c.shortHash) + '</span>' +
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
      if (isMatch && row.scrollIntoView) {
        row.scrollIntoView({ block: 'nearest' });
      }
    });

    if (state.network && state.nodesDataSet) {
      state.network.selectNodes([hash]);
      if (focusGraph) {
        state.network.focus(hash, {
          scale: Math.max(state.network.getScale(), 0.92),
          animation: { duration: 240, easingFunction: 'easeInOutQuad' }
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
      if (detail.hasConflicts || Number(detail.conflictCount || 0) > 0) {
        var cCount = Number(detail.conflictCount || 1);
        badges.push('<span class="tag conflict-tag">' + cCount + (cCount === 1 ? ' conflict' : ' conflicts') + '</span>');
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

    renderParentsRow(detail, null);
  }

  function renderParentsRow(detail, activeBlob) {
    var parentsEl = document.getElementById('detail-parents');
    if (!parentsEl || !detail) {
      return;
    }
    parentsEl.innerHTML = '';
    var parentList = detail.parents || [];
    if (parentList.length === 0) {
      var noneSpan = document.createElement('span');
      noneSpan.className = 'meta-dim';
      noneSpan.textContent = 'None (initial commit)';
      parentsEl.appendChild(noneSpan);
      return;
    }

    var labelOursTheirs = parentList.length > 1 && (
      Boolean(detail.hasConflicts) ||
      Boolean(activeBlob && activeBlob.conflict && activeBlob.parentsDiffer)
    );

    parentList.forEach(function (parentHash, idx) {
      var chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'parent-chip' + (labelOursTheirs ? (idx === 0 ? ' parent-ours' : ' parent-theirs') : '');
      chip.setAttribute('data-parent-hash', parentHash);
      var shortParent = parentHash.slice(0, 7);
      var parentCommit = state.commitsByHash[parentHash];
      var parentSummary = parentCommit ? ' · ' + truncateLine(parentCommit.message, 22) : '';
      var roleLabel;
      if (labelOursTheirs) {
        roleLabel = idx === 0 ? 'p0 · ours (HEAD)' : 'p1 · theirs (merged)';
      } else {
        roleLabel = idx === 0 ? 'p0' : 'p1 (merge)';
      }
      chip.innerHTML =
        '<span class="p-idx">' + escapeHtml(roleLabel) + '</span>' +
        '<span>' + escapeHtml(shortParent) + escapeHtml(parentSummary) + '</span>';
      chip.addEventListener('click', function () {
        selectCommit(parentHash, { focusGraph: true });
      });
      parentsEl.appendChild(chip);
    });
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
        size: fileObj.size,
        conflict: Boolean(fileObj.conflict),
        staged: Boolean(fileObj.staged),
        parentsDiffer: Boolean(fileObj.parentsDiffer),
        oursParent: fileObj.oursParent || null,
        theirsParent: fileObj.theirsParent || null
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
      var classes = 'tree-file';
      if (fileObj.path === state.selectedFilePath) {
        classes += ' active';
      }
      if (fileObj.conflict) {
        classes += ' has-conflict';
      }
      btn.className = classes;
      btn.setAttribute('role', 'treeitem');
      btn.setAttribute('data-file-path', fileObj.path);
      btn.setAttribute('data-conflict', fileObj.conflict ? 'true' : 'false');

      var rightBadgeHtml;
      if (fileObj.conflict) {
        rightBadgeHtml = '<span class="tree-file-right"><span class="conflict-badge">Conflict</span><span class="tree-file-hash">' + escapeHtml(fileObj.shortBlobHash || '') + '</span></span>';
      } else if (fileObj.staged) {
        rightBadgeHtml = '<span class="tree-file-right"><span class="staged-badge">Staged</span><span class="tree-file-hash">' + escapeHtml(fileObj.shortBlobHash || '') + '</span></span>';
      } else {
        rightBadgeHtml = '<span class="tree-file-hash">' + escapeHtml(fileObj.shortBlobHash || '') + '</span>';
      }

      btn.innerHTML =
        '<span class="tree-file-name">' +
          '<span class="tree-file-icon">' + (fileObj.conflict ? '!' : '≡') + '</span>' +
          '<span>' + escapeHtml(fileObj.name) + '</span>' +
        '</span>' +
        rightBadgeHtml;
      btn.addEventListener('click', function () {
        selectFile(commitHash, fileObj.path);
      });
      container.appendChild(btn);
    });
  }

  function renderFileExplorer(detail) {
    var files = detail.files || [];
    var conflictFiles = files.filter(function (f) { return Boolean(f.conflict); });
    var summaryEl = document.getElementById('files-summary');
    if (summaryEl) {
      var summaryText = files.length + (files.length === 1 ? ' tracked file' : ' tracked files');
      if (conflictFiles.length > 0) {
        summaryText += ' · ' + conflictFiles.length + (conflictFiles.length === 1 ? ' conflict' : ' conflicts');
      }
      summaryEl.textContent = summaryText;
    }

    var treeEl = document.getElementById('file-tree');
    if (!treeEl) {
      return;
    }
    treeEl.innerHTML = '';

    if (files.length === 0) {
      state.selectedFilePath = null;
      state.selectedBlob = null;
      treeEl.innerHTML = '<div class="empty-state">No tracked files in this commit.</div>';
      var pathEl = document.getElementById('blob-path');
      var chipEl = document.getElementById('blob-hash-chip');
      var metaEl = document.getElementById('blob-header-meta');
      var viewerEl = document.getElementById('blob-viewer');
      var pillEl = document.getElementById('blob-conflict-pill');
      var switchEl = document.getElementById('conflict-view-switch');
      if (pathEl) {
        pathEl.textContent = 'Empty tree';
      }
      if (chipEl) {
        chipEl.textContent = '';
      }
      if (metaEl) {
        metaEl.textContent = '';
      }
      if (pillEl) {
        pillEl.hidden = true;
      }
      if (switchEl) {
        switchEl.hidden = true;
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
      targetPath = conflictFiles.length > 0 ? conflictFiles[0].path : files[0].path;
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
        state.selectedBlob = blob;
        if (state.selectedCommitDetail) {
          renderParentsRow(state.selectedCommitDetail, blob);
        }
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

  function parseClientConflictSections(content) {
    var rawLines = String(content || '').split(/\r?\n/);
    if (rawLines.length > 1 && rawLines[rawLines.length - 1] === '' && String(content).endsWith('\n')) {
      rawLines.pop();
    }
    var sections = [];
    var contextBuf = [];
    var contextStart = 1;
    var i = 0;
    var n = rawLines.length;

    function isStart(l) {
      var t = l.replace(/\s+$/, '');
      return t === '<<<<<<< HEAD' || t.indexOf('<<<<<<< ') === 0;
    }
    function isSep(l) {
      return l.replace(/\s+$/, '') === '=======';
    }
    function isEnd(l) {
      var t = l.replace(/\s+$/, '');
      return t === '>>>>>>>' || t.indexOf('>>>>>>> ') === 0;
    }

    while (i < n) {
      if (isStart(rawLines[i])) {
        var sepIdx = -1;
        var endIdx = -1;
        for (var j = i + 1; j < n; j++) {
          if (sepIdx < 0) {
            if (isStart(rawLines[j])) {
              break;
            }
            if (isSep(rawLines[j])) {
              sepIdx = j;
            }
          } else if (isEnd(rawLines[j])) {
            endIdx = j;
            break;
          }
        }
        if (sepIdx > i && endIdx > sepIdx) {
          if (contextBuf.length > 0) {
            sections.push({ type: 'context', startLine: contextStart, lines: contextBuf.slice() });
            contextBuf = [];
          }
          sections.push({
            type: 'conflict',
            startLine: i + 1,
            ours: rawLines.slice(i + 1, sepIdx),
            theirs: rawLines.slice(sepIdx + 1, endIdx)
          });
          i = endIdx + 1;
          contextStart = i + 1;
          continue;
        }
      }
      if (contextBuf.length === 0) {
        contextStart = i + 1;
      }
      contextBuf.push(rawLines[i]);
      i++;
    }
    if (contextBuf.length > 0) {
      sections.push({ type: 'context', startLine: contextStart, lines: contextBuf.slice() });
    }
    return sections;
  }

  function renderBlobPreview(blob) {
    var pathEl = document.getElementById('blob-path');
    var chipEl = document.getElementById('blob-hash-chip');
    var metaEl = document.getElementById('blob-header-meta');
    var viewerEl = document.getElementById('blob-viewer');
    var pillEl = document.getElementById('blob-conflict-pill');
    var switchEl = document.getElementById('conflict-view-switch');

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
      if (pillEl) {
        pillEl.hidden = true;
      }
      if (switchEl) {
        switchEl.hidden = true;
      }
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
      if (pillEl) {
        pillEl.hidden = true;
      }
      if (switchEl) {
        switchEl.hidden = true;
      }
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

    var sections = Array.isArray(blob.sections) && blob.sections.length > 0
      ? blob.sections
      : parseClientConflictSections(content);
    var conflictHunks = sections.filter(function (s) { return s && s.type === 'conflict'; });
    var hasConflict = Boolean(blob.conflict) || conflictHunks.length > 0;

    if (pillEl) {
      pillEl.hidden = !hasConflict;
      if (hasConflict) {
        pillEl.textContent = conflictHunks.length === 1 ? '1 conflict' : (conflictHunks.length + ' conflicts');
      }
    }
    if (switchEl) {
      switchEl.hidden = !hasConflict;
      var btnConflict = document.getElementById('btn-mode-conflict');
      var btnRaw = document.getElementById('btn-mode-raw');
      if (btnConflict) {
        btnConflict.classList.toggle('active', state.blobViewMode !== 'raw');
      }
      if (btnRaw) {
        btnRaw.classList.toggle('active', state.blobViewMode === 'raw');
      }
    }

    if (metaEl) {
      var metaParts = [
        rawLines.length + (rawLines.length === 1 ? ' line' : ' lines'),
        formatBytes(blob.size)
      ];
      if (hasConflict) {
        metaParts.unshift(conflictHunks.length + (conflictHunks.length === 1 ? ' conflict hunk' : ' conflict hunks'));
      }
      metaEl.textContent = metaParts.join(' · ');
    }

    if (hasConflict && state.blobViewMode !== 'raw') {
      renderConflictSplitView(viewerEl, blob, sections, conflictHunks.length);
      return;
    }

    var rowsHtml = rawLines.map(function (line, idx) {
      var trimmed = line.replace(/\s+$/, '');
      var rowClass = '';
      if (trimmed === '<<<<<<< HEAD' || trimmed.indexOf('<<<<<<< ') === 0) {
        rowClass = ' class="marker-row marker-ours"';
      } else if (trimmed === '=======') {
        rowClass = ' class="marker-row marker-sep"';
      } else if (trimmed === '>>>>>>>' || trimmed.indexOf('>>>>>>> ') === 0) {
        rowClass = ' class="marker-row marker-theirs"';
      }
      return '<tr' + rowClass + '><td class="ln">' + (idx + 1) + '</td><td class="lc">' + escapeHtml(line) + '</td></tr>';
    }).join('');

    viewerEl.innerHTML = '<table class="code-table"><tbody>' + rowsHtml + '</tbody></table>';
  }

  function renderConflictSplitView(viewerEl, blob, sections, hunkCount) {
    var oursTitle = 'Ours · HEAD';
    var theirsTitle = 'Theirs · Incoming';
    if (blob.oursShortParent) {
      oursTitle += ' (' + blob.oursShortParent + ')';
    }
    if (blob.theirsShortParent) {
      theirsTitle += ' (' + blob.theirsShortParent + ')';
    }

    var parts = [];
    parts.push(
      '<div class="conflict-view">' +
        '<div class="conflict-banner">' +
          '<div class="conflict-banner-left">' +
            '<span class="conflict-badge">Merge Conflict</span>' +
            '<span class="conflict-banner-text">' +
              hunkCount + (hunkCount === 1 ? ' conflicting hunk detected' : ' conflicting hunks detected') +
            '</span>' +
          '</div>' +
          '<div class="conflict-banner-sides">' +
            '<span class="side-pill side-ours">' + escapeHtml(oursTitle) + '</span>' +
            '<span class="side-vs">vs</span>' +
            '<span class="side-pill side-theirs">' + escapeHtml(theirsTitle) + '</span>' +
            '<div class="conflict-actions">' +
              '<button class="pill-btn resolve-btn resolve-ours-btn" id="btn-resolve-ours" type="button" data-resolve-side="ours">Use Ours</button>' +
              '<button class="pill-btn resolve-btn resolve-theirs-btn" id="btn-resolve-theirs" type="button" data-resolve-side="theirs">Use Theirs</button>' +
            '</div>' +
          '</div>' +
        '</div>' +
        '<div class="conflict-resolve-error" id="conflict-resolve-error" hidden></div>'
    );

    var hunkIndex = 0;
    sections.forEach(function (sec) {
      if (!sec) {
        return;
      }
      if (sec.type === 'conflict') {
        hunkIndex++;
        var oursLines = Array.isArray(sec.ours) ? sec.ours : [];
        var theirsLines = Array.isArray(sec.theirs) ? sec.theirs : [];

        var oursBody = oursLines.length === 0
          ? '<div class="conflict-empty-side">(empty / deleted on ours)</div>'
          : '<ol class="conflict-lines">' + oursLines.map(function (l) {
              return '<li><code>' + escapeHtml(l) + '</code></li>';
            }).join('') + '</ol>';

        var theirsBody = theirsLines.length === 0
          ? '<div class="conflict-empty-side">(empty / deleted on theirs)</div>'
          : '<ol class="conflict-lines">' + theirsLines.map(function (l) {
              return '<li><code>' + escapeHtml(l) + '</code></li>';
            }).join('') + '</ol>';

        parts.push(
          '<div class="conflict-hunk">' +
            '<div class="conflict-hunk-head">' +
              '<span>Conflict Hunk #' + hunkIndex + ' (line ' + Number(sec.startLine || 1) + ')</span>' +
              '<span>' + oursLines.length + ' ours / ' + theirsLines.length + ' theirs</span>' +
            '</div>' +
            '<div class="conflict-columns">' +
              '<div class="conflict-pane pane-ours">' +
                '<div class="conflict-pane-title">' + escapeHtml(oursTitle) + '</div>' +
                oursBody +
              '</div>' +
              '<div class="conflict-pane pane-theirs">' +
                '<div class="conflict-pane-title">' + escapeHtml(theirsTitle) + '</div>' +
                theirsBody +
              '</div>' +
            '</div>' +
          '</div>'
        );
      } else {
        var ctxLines = Array.isArray(sec.lines) ? sec.lines : [];
        if (ctxLines.length > 0) {
          var startLn = Number(sec.startLine || 1);
          var rows = ctxLines.map(function (line, i) {
            return '<tr><td class="ln">' + (startLn + i) + '</td><td class="lc">' + escapeHtml(line) + '</td></tr>';
          }).join('');
          parts.push(
            '<div class="conflict-context">' +
              '<table class="code-table"><tbody>' + rows + '</tbody></table>' +
            '</div>'
          );
        }
      }
    });

    parts.push('</div>');
    viewerEl.innerHTML = parts.join('');

    var btnOurs = document.getElementById('btn-resolve-ours');
    var btnTheirs = document.getElementById('btn-resolve-theirs');
    if (btnOurs) {
      btnOurs.addEventListener('click', function () {
        resolveConflictFile(blob.path, 'ours');
      });
    }
    if (btnTheirs) {
      btnTheirs.addEventListener('click', function () {
        resolveConflictFile(blob.path, 'theirs');
      });
    }
  }

  function resolveConflictFile(filePath, side) {
    if (!filePath || !side) {
      return Promise.resolve();
    }
    var btnOurs = document.getElementById('btn-resolve-ours');
    var btnTheirs = document.getElementById('btn-resolve-theirs');
    var errEl = document.getElementById('conflict-resolve-error');
    if (btnOurs) {
      btnOurs.disabled = true;
    }
    if (btnTheirs) {
      btnTheirs.disabled = true;
    }
    if (errEl) {
      errEl.hidden = true;
      errEl.textContent = '';
    }

    return fetch('/api/resolve', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: filePath, side: side })
    })
      .then(function (res) {
        return res.json().catch(function () { return {}; }).then(function (data) {
          if (!res.ok || !data || !data.ok) {
            throw new Error((data && data.error) || 'Failed to resolve conflict.');
          }
          return data;
        });
      })
      .then(function (resolvedBlob) {
        state.selectedFilePath = filePath;
        state.selectedBlob = resolvedBlob;
        renderBlobPreview(resolvedBlob);
        fetch('/api/status').catch(function () {});
        if (state.selectedCommitHash) {
          return selectCommit(state.selectedCommitHash, { focusGraph: false });
        }
      })
      .catch(function (err) {
        if (btnOurs) {
          btnOurs.disabled = false;
        }
        if (btnTheirs) {
          btnTheirs.disabled = false;
        }
        if (errEl) {
          errEl.textContent = err.message || 'Failed to resolve conflict.';
          errEl.hidden = false;
        }
      });
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
        computeGraphLayout(graph);
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
          applyBranchLanePositions();
          fitGraph({
            minZoomLevel: 0.45,
            maxZoomLevel: 1.15,
            animation: { duration: 240, easingFunction: 'easeInOutQuad' }
          });
        }
      });
    }

    var btnZoomReset = document.getElementById('btn-zoom-reset');
    if (btnZoomReset) {
      btnZoomReset.addEventListener('click', function () {
        if (state.network) {
          var target = state.selectedCommitHash || (state.graph && state.graph.headCommit);
          if (target && state.commitsByHash[target]) {
            state.network.focus(target, {
              scale: 1.0,
              animation: { duration: 220, easingFunction: 'easeInOutQuad' }
            });
          } else {
            state.network.moveTo({
              scale: 1.0,
              animation: { duration: 220, easingFunction: 'easeInOutQuad' }
            });
          }
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

    var btnModeConflict = document.getElementById('btn-mode-conflict');
    var btnModeRaw = document.getElementById('btn-mode-raw');
    if (btnModeConflict) {
      btnModeConflict.addEventListener('click', function () {
        state.blobViewMode = 'conflict';
        if (state.selectedBlob) {
          renderBlobPreview(state.selectedBlob);
        }
      });
    }
    if (btnModeRaw) {
      btnModeRaw.addEventListener('click', function () {
        state.blobViewMode = 'raw';
        if (state.selectedBlob) {
          renderBlobPreview(state.selectedBlob);
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
